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

func (r *OzoneClusterReconciler) reconcileS3Gateway(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling S3Gateway")

	if cluster.Spec.S3Gateway == nil || !cluster.Spec.S3Gateway.Enabled {
		// Delete if exists but disabled
		return r.deleteStatefulSet(ctx, cluster, "s3g")
	}

	// Create S3Gateway StatefulSet
	s3gSts := r.buildS3GatewayStatefulSet(cluster)
	if err := controllerutil.SetControllerReference(cluster, s3gSts, r.Scheme); err != nil {
		return err
	}

	found := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{Name: s3gSts.Name, Namespace: s3gSts.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating S3Gateway StatefulSet", "Name", s3gSts.Name)
		return r.Create(ctx, s3gSts)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isStatefulSetEqual(found, s3gSts) {
		found.Spec = s3gSts.Spec
		logger.Info("Updating S3Gateway StatefulSet", "Name", s3gSts.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildS3GatewayStatefulSet(cluster *ozonev1alpha1.OzoneCluster) *appsv1.StatefulSet {
	labels := map[string]string{
		"app":       "ozone",
		"component": "s3g",
		"cluster":   cluster.Name,
	}

	replicas := cluster.Spec.S3Gateway.Replicas
	serviceName := fmt.Sprintf("%s-s3g", cluster.Name)

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
			Value: "s3g",
		},
		{
			Name:  "WAITFOR",
			Value: fmt.Sprintf("%s-om-0.%s-om:9862", cluster.Name, cluster.Name),
		},
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
						"prometheus.io/port":   "9878",
						"prometheus.io/path":   "/prom",
					},
				},
				Spec: corev1.PodSpec{
					SecurityContext: &corev1.PodSecurityContext{
						FSGroup: int64Ptr(1000),
					},
					InitContainers: r.buildInitContainers(cluster, "s3g"),
					Containers: []corev1.Container{
						{
							Name:            "s3g",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/opt/hadoop/bin/ozone"},
							Args:            []string{"s3g"},
							Env:             envVars,
							Ports: []corev1.ContainerPort{
								{Name: "http", ContainerPort: 9878},
							},
							Resources:    cluster.Spec.S3Gateway.Resources,
							VolumeMounts: volumeMounts,
							LivenessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/",
										Port: intstr.FromInt(9878),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
							ReadinessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/",
										Port: intstr.FromInt(9878),
									},
								},
								InitialDelaySeconds: 30,
								PeriodSeconds:       10,
							},
						},
					},
					NodeSelector:     cluster.Spec.S3Gateway.NodeSelector,
					ImagePullSecrets: cluster.Spec.ImagePullSecrets,
					Volumes:          volumes,
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) reconcileRecon(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling Recon")

	if cluster.Spec.Recon == nil || !cluster.Spec.Recon.Enabled {
		// Delete if exists but disabled
		return r.deleteStatefulSet(ctx, cluster, "recon")
	}

	// Create Recon StatefulSet
	reconSts := r.buildReconStatefulSet(cluster)
	if err := controllerutil.SetControllerReference(cluster, reconSts, r.Scheme); err != nil {
		return err
	}

	found := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{Name: reconSts.Name, Namespace: reconSts.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating Recon StatefulSet", "Name", reconSts.Name)
		return r.Create(ctx, reconSts)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isStatefulSetEqual(found, reconSts) {
		found.Spec = reconSts.Spec
		logger.Info("Updating Recon StatefulSet", "Name", reconSts.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildReconStatefulSet(cluster *ozonev1alpha1.OzoneCluster) *appsv1.StatefulSet {
	labels := map[string]string{
		"app":       "ozone",
		"component": "recon",
		"cluster":   cluster.Name,
	}

	replicas := int32(1) // Recon is always single instance
	serviceName := fmt.Sprintf("%s-recon", cluster.Name)

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
			Name:      "data",
			MountPath: "/data",
		},
	}

	envVars := []corev1.EnvVar{
		{
			Name:  "OZONE_COMPONENT",
			Value: "recon",
		},
		{
			Name:  "WAITFOR",
			Value: fmt.Sprintf("%s-om-0.%s-om:9862", cluster.Name, cluster.Name),
		},
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
						"prometheus.io/port":   "9891",
						"prometheus.io/path":   "/prom",
					},
				},
				Spec: corev1.PodSpec{
					SecurityContext: &corev1.PodSecurityContext{
						FSGroup: int64Ptr(1000),
					},
					InitContainers: r.buildInitContainers(cluster, "recon"),
					Containers: []corev1.Container{
						{
							Name:            "recon",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/opt/hadoop/bin/ozone"},
							Args:            []string{"recon"},
							Env:             envVars,
							Ports: []corev1.ContainerPort{
								{Name: "http", ContainerPort: 9888},
								{Name: "metrics", ContainerPort: 9891},
							},
							Resources:    cluster.Spec.Recon.Resources,
							VolumeMounts: volumeMounts,
							LivenessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/api/v1/task/status",
										Port: intstr.FromInt(9888),
									},
								},
								InitialDelaySeconds: 60,
								PeriodSeconds:       30,
							},
							ReadinessProbe: &corev1.Probe{
								ProbeHandler: corev1.ProbeHandler{
									HTTPGet: &corev1.HTTPGetAction{
										Path: "/api/v1/task/status",
										Port: intstr.FromInt(9888),
									},
								},
								InitialDelaySeconds: 60,
								PeriodSeconds:       30,
							},
						},
					},
					ImagePullSecrets: cluster.Spec.ImagePullSecrets,
					Volumes:          volumes,
				},
			},
			VolumeClaimTemplates: []corev1.PersistentVolumeClaim{
				{
					ObjectMeta: metav1.ObjectMeta{
						Name: "data",
					},
					Spec: corev1.PersistentVolumeClaimSpec{
						AccessModes: []corev1.PersistentVolumeAccessMode{
							corev1.ReadWriteOnce,
						},
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								corev1.ResourceStorage: cluster.Spec.Recon.StorageSize,
							},
						},
						StorageClassName: cluster.Spec.Recon.StorageClass,
					},
				},
			},
		},
	}
}