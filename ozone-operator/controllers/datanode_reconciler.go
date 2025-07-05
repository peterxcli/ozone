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

func (r *OzoneClusterReconciler) reconcileDatanodes(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling Datanodes")

	// Create Datanode StatefulSet
	dnSts := r.buildDatanodeStatefulSet(cluster)
	if err := controllerutil.SetControllerReference(cluster, dnSts, r.Scheme); err != nil {
		return err
	}

	found := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{Name: dnSts.Name, Namespace: dnSts.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating Datanode StatefulSet", "Name", dnSts.Name)
		return r.Create(ctx, dnSts)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isStatefulSetEqual(found, dnSts) {
		found.Spec = dnSts.Spec
		logger.Info("Updating Datanode StatefulSet", "Name", dnSts.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildDatanodeStatefulSet(cluster *ozonev1alpha1.OzoneCluster) *appsv1.StatefulSet {
	labels := map[string]string{
		"app":       "ozone",
		"component": "datanode",
		"cluster":   cluster.Name,
	}

	replicas := cluster.Spec.Datanodes.Replicas
	serviceName := fmt.Sprintf("%s-datanode", cluster.Name)

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
	}

	// Add data volume mounts
	volumeClaimTemplates := []corev1.PersistentVolumeClaim{}
	for i, dataVolume := range cluster.Spec.Datanodes.DataVolumes {
		volumeName := fmt.Sprintf("data%d", i)
		mountPath := dataVolume.MountPath
		if mountPath == "" {
			mountPath = fmt.Sprintf("/data/disk%d", i+1)
		}

		volumeMounts = append(volumeMounts, corev1.VolumeMount{
			Name:      volumeName,
			MountPath: mountPath,
		})

		volumeClaimTemplates = append(volumeClaimTemplates, corev1.PersistentVolumeClaim{
			ObjectMeta: metav1.ObjectMeta{
				Name: volumeName,
			},
			Spec: corev1.PersistentVolumeClaimSpec{
				AccessModes: []corev1.PersistentVolumeAccessMode{
					corev1.ReadWriteOnce,
				},
				Resources: corev1.ResourceRequirements{
					Requests: corev1.ResourceList{
						corev1.ResourceStorage: dataVolume.Size,
					},
				},
				StorageClassName: dataVolume.StorageClass,
			},
		})
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
			Value: "datanode",
		},
		{
			Name:  "WAITFOR",
			Value: fmt.Sprintf("%s-scm-0.%s-scm:9876,%s-om-0.%s-om:9862", cluster.Name, cluster.Name, cluster.Name, cluster.Name),
		},
	}

	// Build data dirs environment variable
	dataDirs := ""
	for i := range cluster.Spec.Datanodes.DataVolumes {
		if i > 0 {
			dataDirs += ","
		}
		mountPath := cluster.Spec.Datanodes.DataVolumes[i].MountPath
		if mountPath == "" {
			mountPath = fmt.Sprintf("/data/disk%d", i+1)
		}
		dataDirs += mountPath
	}
	envVars = append(envVars, corev1.EnvVar{
		Name:  "HDDS_DATANODE_DIR",
		Value: dataDirs,
	})

	// Anti-affinity to spread datanodes across nodes
	affinity := cluster.Spec.Datanodes.Affinity
	if affinity == nil {
		affinity = &corev1.Affinity{
			PodAntiAffinity: &corev1.PodAntiAffinity{
				PreferredDuringSchedulingIgnoredDuringExecution: []corev1.WeightedPodAffinityTerm{
					{
						Weight: 100,
						PodAffinityTerm: corev1.PodAffinityTerm{
							LabelSelector: &metav1.LabelSelector{
								MatchLabels: labels,
							},
							TopologyKey: "kubernetes.io/hostname",
						},
					},
				},
			},
		}
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
						"prometheus.io/port":   "9882",
						"prometheus.io/path":   "/prom",
					},
				},
				Spec: corev1.PodSpec{
					SecurityContext: &corev1.PodSecurityContext{
						FSGroup: int64Ptr(1000),
					},
					InitContainers: r.buildInitContainers(cluster, "datanode"),
					Containers: []corev1.Container{
						{
							Name:            "datanode",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/opt/hadoop/bin/ozone"},
							Args:            []string{"datanode"},
							Env:             envVars,
							Ports: []corev1.ContainerPort{
								{Name: "rpc", ContainerPort: 9858},
								{Name: "http", ContainerPort: 9882},
								{Name: "data", ContainerPort: 9859},
							},
							Resources:    cluster.Spec.Datanodes.Resources,
							VolumeMounts: volumeMounts,
							LivenessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									TCPSocket: &corev1.TCPSocketAction{
										Port: intstr.FromInt(9858),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
							ReadinessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/prom",
										Port: intstr.FromInt(9882),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
						},
					},
					NodeSelector:     cluster.Spec.Datanodes.NodeSelector,
					Affinity:         affinity,
					Tolerations:      cluster.Spec.Datanodes.Tolerations,
					ImagePullSecrets: cluster.Spec.ImagePullSecrets,
					Volumes:          volumes,
				},
			},
			VolumeClaimTemplates: volumeClaimTemplates,
		},
	}
}