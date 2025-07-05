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
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

func (r *OzoneClusterReconciler) reconcileServices(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling Services")

	// Create SCM Service
	if err := r.reconcileService(ctx, cluster, r.buildSCMService(cluster), logger); err != nil {
		return err
	}

	// Create OM Service
	if err := r.reconcileService(ctx, cluster, r.buildOMService(cluster), logger); err != nil {
		return err
	}

	// Create Datanode Service
	if err := r.reconcileService(ctx, cluster, r.buildDatanodeService(cluster), logger); err != nil {
		return err
	}

	// Create S3Gateway Service if enabled
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		if err := r.reconcileService(ctx, cluster, r.buildS3GatewayService(cluster), logger); err != nil {
			return err
		}
	}

	// Create Recon Service if enabled
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		if err := r.reconcileService(ctx, cluster, r.buildReconService(cluster), logger); err != nil {
			return err
		}
	}

	return nil
}

func (r *OzoneClusterReconciler) reconcileService(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, svc *corev1.Service, logger logr.Logger) error {
	if err := controllerutil.SetControllerReference(cluster, svc, r.Scheme); err != nil {
		return err
	}

	found := &corev1.Service{}
	err := r.Get(ctx, types.NamespacedName{Name: svc.Name, Namespace: svc.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating Service", "Name", svc.Name)
		return r.Create(ctx, svc)
	} else if err != nil {
		return err
	}

	// Update if needed (services are mostly immutable, so we check specific fields)
	if !isServiceEqual(found, svc) {
		found.Spec.Selector = svc.Spec.Selector
		found.Spec.Ports = svc.Spec.Ports
		logger.Info("Updating Service", "Name", svc.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildSCMService(cluster *ozonev1alpha1.OzoneCluster) *corev1.Service {
	labels := map[string]string{
		"app":       "ozone",
		"component": "scm",
		"cluster":   cluster.Name,
	}

	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-scm", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None", // Headless service for StatefulSet
			Selector:  labels,
			Ports: []corev1.ServicePort{
				{
					Name:       "rpc",
					Port:       9860,
					TargetPort: intstr.FromInt(9860),
				},
				{
					Name:       "grpc",
					Port:       9876,
					TargetPort: intstr.FromInt(9876),
				},
				{
					Name:       "http",
					Port:       9876,
					TargetPort: intstr.FromInt(9876),
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) buildOMService(cluster *ozonev1alpha1.OzoneCluster) *corev1.Service {
	labels := map[string]string{
		"app":       "ozone",
		"component": "om",
		"cluster":   cluster.Name,
	}

	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-om", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None", // Headless service for StatefulSet
			Selector:  labels,
			Ports: []corev1.ServicePort{
				{
					Name:       "rpc",
					Port:       9862,
					TargetPort: intstr.FromInt(9862),
				},
				{
					Name:       "http",
					Port:       9874,
					TargetPort: intstr.FromInt(9874),
				},
				{
					Name:       "ratis",
					Port:       9872,
					TargetPort: intstr.FromInt(9872),
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) buildDatanodeService(cluster *ozonev1alpha1.OzoneCluster) *corev1.Service {
	labels := map[string]string{
		"app":       "ozone",
		"component": "datanode",
		"cluster":   cluster.Name,
	}

	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-datanode", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None", // Headless service for StatefulSet
			Selector:  labels,
			Ports: []corev1.ServicePort{
				{
					Name:       "rpc",
					Port:       9858,
					TargetPort: intstr.FromInt(9858),
				},
				{
					Name:       "http",
					Port:       9882,
					TargetPort: intstr.FromInt(9882),
				},
				{
					Name:       "data",
					Port:       9859,
					TargetPort: intstr.FromInt(9859),
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) buildS3GatewayService(cluster *ozonev1alpha1.OzoneCluster) *corev1.Service {
	labels := map[string]string{
		"app":       "ozone",
		"component": "s3g",
		"cluster":   cluster.Name,
	}

	serviceType := cluster.Spec.S3Gateway.ServiceType
	if serviceType == "" {
		serviceType = corev1.ServiceTypeClusterIP
	}

	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-s3g", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: corev1.ServiceSpec{
			Type:     serviceType,
			Selector: labels,
			Ports: []corev1.ServicePort{
				{
					Name:       "http",
					Port:       9878,
					TargetPort: intstr.FromInt(9878),
				},
			},
		},
	}
}

func (r *OzoneClusterReconciler) buildReconService(cluster *ozonev1alpha1.OzoneCluster) *corev1.Service {
	labels := map[string]string{
		"app":       "ozone",
		"component": "recon",
		"cluster":   cluster.Name,
	}

	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-recon", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "None", // Headless service for StatefulSet
			Selector:  labels,
			Ports: []corev1.ServicePort{
				{
					Name:       "http",
					Port:       9888,
					TargetPort: intstr.FromInt(9888),
				},
				{
					Name:       "metrics",
					Port:       9891,
					TargetPort: intstr.FromInt(9891),
				},
			},
		},
	}
}

func isServiceEqual(a, b *corev1.Service) bool {
	if len(a.Spec.Ports) != len(b.Spec.Ports) {
		return false
	}
	
	// Check if selectors are equal
	if len(a.Spec.Selector) != len(b.Spec.Selector) {
		return false
	}
	for k, v := range a.Spec.Selector {
		if b.Spec.Selector[k] != v {
			return false
		}
	}
	
	// Check if ports are equal
	for i, port := range a.Spec.Ports {
		if port.Name != b.Spec.Ports[i].Name ||
			port.Port != b.Spec.Ports[i].Port ||
			port.TargetPort.IntVal != b.Spec.Ports[i].TargetPort.IntVal {
			return false
		}
	}
	
	return true
}