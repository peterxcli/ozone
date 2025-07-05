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

func (r *OzoneClusterReconciler) reconcileOM(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling OM")

	// Create OM StatefulSet
	omSts := r.buildOMStatefulSet(cluster)
	if err := controllerutil.SetControllerReference(cluster, omSts, r.Scheme); err != nil {
		return err
	}

	found := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{Name: omSts.Name, Namespace: omSts.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating OM StatefulSet", "Name", omSts.Name)
		return r.Create(ctx, omSts)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isStatefulSetEqual(found, omSts) {
		found.Spec = omSts.Spec
		logger.Info("Updating OM StatefulSet", "Name", omSts.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildOMStatefulSet(cluster *ozonev1alpha1.OzoneCluster) *appsv1.StatefulSet {
	labels := map[string]string{
		"app":       "ozone",
		"component": "om",
		"cluster":   cluster.Name,
	}

	replicas := cluster.Spec.OM.Replicas
	serviceName := fmt.Sprintf("%s-om", cluster.Name)

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
			Value: "om",
		},
		{
			Name:  "ENSURE_OM_INITIALIZED",
			Value: "/data/metadata/om/current/VERSION",
		},
		{
			Name:  "WAITFOR",
			Value: fmt.Sprintf("%s-scm-0.%s-scm:9876", cluster.Name, cluster.Name),
		},
	}

	// Add HA environment variables
	if cluster.Spec.OM.EnableHA && replicas > 1 {
		envVars = append(envVars, corev1.EnvVar{
			Name:  "OZONE_OM_HA_ENABLE",
			Value: "true",
		})
		
		// Build service ID
		serviceID := cluster.Name
		envVars = append(envVars, corev1.EnvVar{
			Name:  "OZONE_OM_SERVICE_ID",
			Value: serviceID,
		})
		
		// Build OM nodes list
		omNodes := ""
		for i := int32(0); i < replicas; i++ {
			if i > 0 {
				omNodes += ","
			}
			omNodes += fmt.Sprintf("om%d", i)
		}
		envVars = append(envVars, corev1.EnvVar{
			Name:  "OZONE_OM_NODES",
			Value: omNodes,
		})
		
		// Set node ID from pod ordinal
		envVars = append(envVars, corev1.EnvVar{
			Name: "OZONE_OM_NODE_ID",
			ValueFrom: &corev1.EnvVarSource{
				FieldRef: &corev1.ObjectFieldSelector{
					FieldPath: "metadata.name",
				},
			},
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
						"prometheus.io/port":   "9874",
						"prometheus.io/path":   "/prom",
					},
				},
				Spec: corev1.PodSpec{
					SecurityContext: &corev1.PodSecurityContext{
						FSGroup: int64Ptr(1000),
					},
					InitContainers: r.buildInitContainers(cluster, "om"),
					Containers: []corev1.Container{
						{
							Name:            "om",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/opt/hadoop/bin/ozone"},
							Args:            []string{"om"},
							Env:             envVars,
							Ports: []corev1.ContainerPort{
								{Name: "rpc", ContainerPort: 9862},
								{Name: "http", ContainerPort: 9874},
								{Name: "ratis", ContainerPort: 9872},
							},
							Resources:    cluster.Spec.OM.Resources,
							VolumeMounts: volumeMounts,
							LivenessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									TCPSocket: &corev1.TCPSocketAction{
										Port: intstr.FromInt(9862),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
							ReadinessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/prom",
										Port: intstr.FromInt(9874),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
						},
					},
					NodeSelector:     cluster.Spec.OM.NodeSelector,
					Affinity:         cluster.Spec.OM.Affinity,
					Tolerations:      cluster.Spec.OM.Tolerations,
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
								corev1.ResourceStorage: cluster.Spec.OM.StorageSize,
							},
						},
						StorageClassName: cluster.Spec.OM.StorageClass,
					},
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) isOMReady(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	sts := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{
		Name:      fmt.Sprintf("%s-om", cluster.Name),
		Namespace: cluster.Namespace,
	}, sts)
	if err != nil {
		return false, err
	}

	return sts.Status.ReadyReplicas == cluster.Spec.OM.Replicas, nil
}