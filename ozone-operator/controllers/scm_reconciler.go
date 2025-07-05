/*
Copyright 2024 The Apache Software Foundation.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controllers

import (
	"context"
	"fmt"

	"github.com/go-logr/logr"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

func (r *OzoneClusterReconciler) reconcileSCM(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling SCM")

	// Create SCM StatefulSet
	scmSts := r.buildSCMStatefulSet(cluster)
	if err := controllerutil.SetControllerReference(cluster, scmSts, r.Scheme); err != nil {
		return err
	}

	found := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{Name: scmSts.Name, Namespace: scmSts.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating SCM StatefulSet", "Name", scmSts.Name)
		return r.Create(ctx, scmSts)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isStatefulSetEqual(found, scmSts) {
		found.Spec = scmSts.Spec
		logger.Info("Updating SCM StatefulSet", "Name", scmSts.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildSCMStatefulSet(cluster *ozonev1alpha1.OzoneCluster) *appsv1.StatefulSet {
	labels := map[string]string{
		"app":       "ozone",
		"component": "scm",
		"cluster":   cluster.Name,
	}

	replicas := cluster.Spec.SCM.Replicas
	serviceName := fmt.Sprintf("%s-scm", cluster.Name)

	volumes := []corev1.Volume{
		{
			Name: "config",
			VolumeSource: corev1.VolumeSource{
				ConfigMap: &corev1.ConfigMapVolumeSource{
					LocalObjectReference: corev1.LocalObjectReference{
						Name: fmt.Sprintf("%s-config", cluster.Name),
					},
				},
			},
		},
	}

	volumeMounts := []corev1.VolumeMount{
		{
			Name:      "config",
			MountPath: "/opt/hadoop/etc/hadoop",
		},
		{
			Name:      "metadata",
			MountPath: "/data/metadata",
		},
	}

	// Add security volumes if enabled
	if cluster.Spec.Security != nil && cluster.Spec.Security.Enabled {
		if cluster.Spec.Security.TLSEnabled && cluster.Spec.Security.CertificateSecret != nil {
			volumes = append(volumes, corev1.Volume{
				Name: "certificates",
				VolumeSource: corev1.VolumeSource{
					Secret: &corev1.SecretVolumeSource{
						SecretName: cluster.Spec.Security.CertificateSecret.Name,
					},
				},
			})
			volumeMounts = append(volumeMounts, corev1.VolumeMount{
				Name:      "certificates",
				MountPath: "/opt/hadoop/etc/security/certificates",
				ReadOnly:  true,
			})
		}
	}

	envVars := []corev1.EnvVar{
		{
			Name:  "OZONE_COMPONENT",
			Value: "scm",
		},
		{
			Name:  "ENSURE_SCM_INITIALIZED",
			Value: "/data/metadata/scm/current/VERSION",
		},
	}

	// Add HA environment variables
	if cluster.Spec.SCM.EnableHA && replicas > 1 {
		envVars = append(envVars, corev1.EnvVar{
			Name:  "OZONE_SCM_HA_ENABLE",
			Value: "true",
		})
		
		// Build SCM nodes list
		scmNodes := ""
		for i := int32(0); i < replicas; i++ {
			if i > 0 {
				scmNodes += ","
			}
			scmNodes += fmt.Sprintf("scm%d=%s-%d.%s:9865", i, serviceName, i, serviceName)
		}
		envVars = append(envVars, corev1.EnvVar{
			Name:  "OZONE_SCM_NODES",
			Value: scmNodes,
		})
	}

	return &appsv1.StatefulSet{
		ObjectMeta: metav1.ObjectMeta{
			Name:      serviceName,
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: appsv1.StatefulSetSpec{
			ServiceName: serviceName,
			Replicas:    &replicas,
			Selector: &metav1.LabelSelector{
				MatchLabels: labels,
			},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{
					Labels: labels,
					Annotations: map[string]string{
						"prometheus.io/scrape": "true",
						"prometheus.io/port":   "9876",
						"prometheus.io/path":   "/prom",
					},
				},
				Spec: corev1.PodSpec{
					SecurityContext: &corev1.PodSecurityContext{
						FSGroup: int64Ptr(1000),
					},
					InitContainers: r.buildInitContainers(cluster, "scm"),
					Containers: []corev1.Container{
						{
							Name:            "scm",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/opt/hadoop/bin/ozone"},
							Args:            []string{"scm"},
							Env:             envVars,
							Ports: []corev1.ContainerPort{
								{Name: "rpc", ContainerPort: 9860},
								{Name: "grpc", ContainerPort: 9876},
								{Name: "http", ContainerPort: 9876},
							},
							Resources:    cluster.Spec.SCM.Resources,
							VolumeMounts: volumeMounts,
							LivenessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									TCPSocket: &corev1.TCPSocketAction{
										Port: intstr.FromInt(9876),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
							ReadinessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/prom",
										Port: intstr.FromInt(9876),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
						},
					},
					NodeSelector:     cluster.Spec.SCM.NodeSelector,
					Affinity:         cluster.Spec.SCM.Affinity,
					Tolerations:      cluster.Spec.SCM.Tolerations,
					ImagePullSecrets: cluster.Spec.ImagePullSecrets,
					Volumes:          volumes,
				},
			},
			VolumeClaimTemplates: []corev1.PersistentVolumeClaim{
				{
					ObjectMeta: metav1.ObjectMeta{
						Name: "metadata",
					},
					Spec: corev1.PersistentVolumeClaimSpec{
						AccessModes: []corev1.PersistentVolumeAccessMode{
							corev1.ReadWriteOnce,
						},
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								corev1.ResourceStorage: cluster.Spec.SCM.StorageSize,
							},
						},
						StorageClassName: cluster.Spec.SCM.StorageClass,
					},
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) isSCMReady(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	sts := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{
		Name:      fmt.Sprintf("%s-scm", cluster.Name),
		Namespace: cluster.Namespace,
	}, sts)
	if err != nil {
		return false, err
	}

	return sts.Status.ReadyReplicas == cluster.Spec.SCM.Replicas, nil
}

func (r *OzoneClusterReconciler) buildInitContainers(cluster *ozonev1alpha1.OzoneCluster, component string) []corev1.Container {
	initContainers := []corev1.Container{}

	// Wait for dependent services
	if component == "om" {
		initContainers = append(initContainers, corev1.Container{
			Name:            "wait-for-scm",
			Image:           cluster.Spec.Image,
			ImagePullPolicy: cluster.Spec.ImagePullPolicy,
			Command:         []string{"/bin/bash"},
			Args: []string{
				"-c",
				fmt.Sprintf("until nc -z %s-scm-0.%s-scm 9876; do echo waiting for scm; sleep 2; done", cluster.Name, cluster.Name),
			},
		})
	}

	if component == "datanode" {
		initContainers = append(initContainers, corev1.Container{
			Name:            "wait-for-om",
			Image:           cluster.Spec.Image,
			ImagePullPolicy: cluster.Spec.ImagePullPolicy,
			Command:         []string{"/bin/bash"},
			Args: []string{
				"-c",
				fmt.Sprintf("until nc -z %s-om-0.%s-om 9862; do echo waiting for om; sleep 2; done", cluster.Name, cluster.Name),
			},
		})
	}

	return initContainers
}

func isStatefulSetEqual(a, b *appsv1.StatefulSet) bool {
	// Simple comparison - in production, use deep equality or specific field comparison
	return *a.Spec.Replicas == *b.Spec.Replicas &&
		a.Spec.Template.Spec.Containers[0].Image == b.Spec.Template.Spec.Containers[0].Image
}

func int64Ptr(i int64) *int64 {
	return &i
}