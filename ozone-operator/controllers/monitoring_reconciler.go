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
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

func (r *OzoneClusterReconciler) reconcileMonitoring(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	if cluster.Spec.Monitoring == nil || !cluster.Spec.Monitoring.Enabled {
		// Delete monitoring resources if disabled
		return r.deleteMonitoringResources(ctx, cluster)
	}

	// Create ServiceMonitors if Prometheus Operator is enabled
	if cluster.Spec.Monitoring.PrometheusOperator != nil && cluster.Spec.Monitoring.PrometheusOperator.ServiceMonitor {
		if err := r.reconcileServiceMonitors(ctx, cluster, logger); err != nil {
			return err
		}
	}

	// Create Grafana dashboards if enabled
	if cluster.Spec.Monitoring.GrafanaDashboard != nil && cluster.Spec.Monitoring.GrafanaDashboard.Enabled {
		if err := r.reconcileGrafanaDashboards(ctx, cluster, logger); err != nil {
			return err
		}
	}

	return nil
}

func (r *OzoneClusterReconciler) reconcileServiceMonitors(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	components := []struct {
		name     string
		port     string
		path     string
		enabled  bool
	}{
		{"scm", "9876", "/prom", true},
		{"om", "9874", "/prom", true},
		{"datanode", "9882", "/prom", true},
		{"s3g", "9878", "/prom", cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled},
		{"recon", "9891", "/prom", cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled},
	}

	for _, component := range components {
		if !component.enabled {
			continue
		}

		sm := r.buildServiceMonitor(cluster, component.name, component.port, component.path)
		if err := r.reconcileServiceMonitor(ctx, cluster, sm, logger); err != nil {
			return err
		}
	}

	return nil
}

func (r *OzoneClusterReconciler) buildServiceMonitor(cluster *ozonev1alpha1.OzoneCluster, component, port, path string) *unstructured.Unstructured {
	labels := map[string]string{
		"app":       "ozone",
		"component": component,
		"cluster":   cluster.Name,
	}

	// Add custom labels from spec
	if cluster.Spec.Monitoring.PrometheusOperator.Labels != nil {
		for k, v := range cluster.Spec.Monitoring.PrometheusOperator.Labels {
			labels[k] = v
		}
	}

	interval := cluster.Spec.Monitoring.PrometheusOperator.Interval
	if interval == "" {
		interval = "30s"
	}

	sm := &unstructured.Unstructured{
		Object: map[string]interface{}{
			"apiVersion": "monitoring.coreos.com/v1",
			"kind":       "ServiceMonitor",
			"metadata": map[string]interface{}{
				"name":      fmt.Sprintf("%s-%s", cluster.Name, component),
				"namespace": cluster.Namespace,
				"labels":    labels,
			},
			"spec": map[string]interface{}{
				"selector": map[string]interface{}{
					"matchLabels": map[string]string{
						"app":       "ozone",
						"component": component,
						"cluster":   cluster.Name,
					},
				},
				"endpoints": []map[string]interface{}{
					{
						"port":     port,
						"path":     path,
						"interval": interval,
					},
				},
			},
		},
	}

	return sm
}

func (r *OzoneClusterReconciler) reconcileServiceMonitor(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, sm *unstructured.Unstructured, logger logr.Logger) error {
	if err := controllerutil.SetControllerReference(cluster, sm, r.Scheme); err != nil {
		return err
	}

	found := &unstructured.Unstructured{}
	found.SetGroupVersionKind(schema.GroupVersionKind{
		Group:   "monitoring.coreos.com",
		Version: "v1",
		Kind:    "ServiceMonitor",
	})

	err := r.Get(ctx, types.NamespacedName{
		Name:      sm.GetName(),
		Namespace: sm.GetNamespace(),
	}, found)

	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating ServiceMonitor", "Name", sm.GetName())
		return r.Create(ctx, sm)
	} else if err != nil {
		return err
	}

	// Update if needed
	found.Object["spec"] = sm.Object["spec"]
	logger.Info("Updating ServiceMonitor", "Name", sm.GetName())
	return r.Update(ctx, found)
}

func (r *OzoneClusterReconciler) reconcileGrafanaDashboards(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	dashboards := []struct {
		name     string
		filename string
	}{
		{"overview", "ozone-overview.json"},
		{"scm", "ozone-scm.json"},
		{"om", "ozone-om.json"},
		{"datanode", "ozone-datanode.json"},
	}

	for _, dashboard := range dashboards {
		cm := r.buildDashboardConfigMap(cluster, dashboard.name, dashboard.filename)
		if err := r.reconcileDashboardConfigMap(ctx, cluster, cm, logger); err != nil {
			return err
		}
	}

	return nil
}

func (r *OzoneClusterReconciler) buildDashboardConfigMap(cluster *ozonev1alpha1.OzoneCluster, name, filename string) *corev1.ConfigMap {
	labels := map[string]string{
		"app":       "ozone",
		"component": "grafana-dashboard",
		"cluster":   cluster.Name,
	}

	// Add Grafana dashboard labels
	if cluster.Spec.Monitoring.GrafanaDashboard.Labels != nil {
		for k, v := range cluster.Spec.Monitoring.GrafanaDashboard.Labels {
			labels[k] = v
		}
	} else {
		// Default Grafana dashboard label
		labels["grafana_dashboard"] = "1"
	}

	// Generate dashboard JSON based on the component
	dashboardJSON := r.generateDashboardJSON(cluster, name)

	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-grafana-%s", cluster.Name, name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Data: map[string]string{
			filename: dashboardJSON,
		},
	}
}

func (r *OzoneClusterReconciler) generateDashboardJSON(cluster *ozonev1alpha1.OzoneCluster, component string) string {
	// In production, these would be complete Grafana dashboard JSON files
	// For now, return a minimal dashboard template
	return fmt.Sprintf(`{
  "dashboard": {
    "title": "Ozone %s Dashboard - %s",
    "uid": "%s-%s",
    "tags": ["ozone", "%s"],
    "timezone": "browser",
    "panels": [
      {
        "title": "Sample Panel",
        "targets": [
          {
            "expr": "up{job=\"%s-%s\"}"
          }
        ]
      }
    ]
  }
}`, component, cluster.Name, cluster.Name, component, component, cluster.Name, component)
}

func (r *OzoneClusterReconciler) reconcileDashboardConfigMap(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, cm *corev1.ConfigMap, logger logr.Logger) error {
	if err := controllerutil.SetControllerReference(cluster, cm, r.Scheme); err != nil {
		return err
	}

	found := &corev1.ConfigMap{}
	err := r.Get(ctx, types.NamespacedName{
		Name:      cm.Name,
		Namespace: cm.Namespace,
	}, found)

	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating Grafana dashboard ConfigMap", "Name", cm.Name)
		return r.Create(ctx, cm)
	} else if err != nil {
		return err
	}

	// Update if needed
	found.Data = cm.Data
	found.Labels = cm.Labels
	logger.Info("Updating Grafana dashboard ConfigMap", "Name", cm.Name)
	return r.Update(ctx, found)
}

func (r *OzoneClusterReconciler) deleteMonitoringResources(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	// Delete ServiceMonitors
	components := []string{"scm", "om", "datanode", "s3g", "recon"}
	for _, component := range components {
		sm := &unstructured.Unstructured{}
		sm.SetGroupVersionKind(schema.GroupVersionKind{
			Group:   "monitoring.coreos.com",
			Version: "v1",
			Kind:    "ServiceMonitor",
		})
		sm.SetName(fmt.Sprintf("%s-%s", cluster.Name, component))
		sm.SetNamespace(cluster.Namespace)
		
		if err := client.IgnoreNotFound(r.Delete(ctx, sm)); err != nil {
			return err
		}
	}

	// Delete Grafana dashboards
	dashboards := []string{"overview", "scm", "om", "datanode"}
	for _, dashboard := range dashboards {
		cm := &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{
				Name:      fmt.Sprintf("%s-grafana-%s", cluster.Name, dashboard),
				Namespace: cluster.Namespace,
			},
		}
		
		if err := client.IgnoreNotFound(r.Delete(ctx, cm)); err != nil {
			return err
		}
	}

	return nil
}