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

package upgrade

import (
	"context"
	"fmt"
	"time"

	"github.com/go-logr/logr"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

// Manager handles Ozone cluster upgrades
type Manager struct {
	client client.Client
	logger logr.Logger
}

// NewManager creates a new upgrade manager
func NewManager(client client.Client, logger logr.Logger) *Manager {
	return &Manager{
		client: client,
		logger: logger,
	}
}

// UpgradeCluster performs a rolling upgrade of the Ozone cluster
func (m *Manager) UpgradeCluster(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	m.logger.Info("Starting cluster upgrade", "from", cluster.Status.Version, "to", cluster.Spec.Version)

	// Upgrade order: SCM -> OM -> Datanodes -> S3Gateway -> Recon
	components := []struct {
		name     string
		replicas int32
		enabled  bool
	}{
		{"scm", cluster.Spec.SCM.Replicas, true},
		{"om", cluster.Spec.OM.Replicas, true},
		{"datanode", cluster.Spec.Datanodes.Replicas, true},
		{"s3g", cluster.Spec.S3Gateway.Replicas, cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled},
		{"recon", 1, cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled},
	}

	for _, component := range components {
		if !component.enabled {
			continue
		}

		completed, err := m.upgradeComponent(ctx, cluster, component.name, component.replicas)
		if err != nil {
			return false, fmt.Errorf("failed to upgrade %s: %w", component.name, err)
		}

		if !completed {
			m.logger.Info("Component upgrade in progress", "component", component.name)
			return false, nil
		}

		m.logger.Info("Component upgrade completed", "component", component.name)
	}

	return true, nil
}

// upgradeComponent upgrades a single component using rolling update
func (m *Manager) upgradeComponent(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, componentName string, replicas int32) (bool, error) {
	stsName := fmt.Sprintf("%s-%s", cluster.Name, componentName)
	sts := &appsv1.StatefulSet{}
	
	if err := m.client.Get(ctx, types.NamespacedName{
		Name:      stsName,
		Namespace: cluster.Namespace,
	}, sts); err != nil {
		return false, err
	}

	// Check if already on target version
	currentImage := sts.Spec.Template.Spec.Containers[0].Image
	targetImage := cluster.Spec.Image
	
	if currentImage == targetImage {
		// Check if all pods are ready
		if sts.Status.ReadyReplicas == replicas && sts.Status.UpdatedReplicas == replicas {
			return true, nil
		}
	}

	// Update the StatefulSet image
	if currentImage != targetImage {
		m.logger.Info("Updating StatefulSet image", "component", componentName, "from", currentImage, "to", targetImage)
		sts.Spec.Template.Spec.Containers[0].Image = targetImage
		
		// Update the StatefulSet
		if err := m.client.Update(ctx, sts); err != nil {
			return false, err
		}
	}

	// Wait for rolling update to complete
	return m.waitForRollingUpdate(ctx, cluster, componentName, replicas)
}

// waitForRollingUpdate waits for a StatefulSet rolling update to complete
func (m *Manager) waitForRollingUpdate(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, componentName string, replicas int32) (bool, error) {
	stsName := fmt.Sprintf("%s-%s", cluster.Name, componentName)
	sts := &appsv1.StatefulSet{}
	
	if err := m.client.Get(ctx, types.NamespacedName{
		Name:      stsName,
		Namespace: cluster.Namespace,
	}, sts); err != nil {
		return false, err
	}

	// Check if update is complete
	if sts.Status.UpdatedReplicas == replicas && sts.Status.ReadyReplicas == replicas {
		// Verify all pods are healthy
		return m.verifyPodsHealthy(ctx, cluster, componentName, replicas)
	}

	m.logger.Info("Waiting for rolling update", 
		"component", componentName,
		"updated", sts.Status.UpdatedReplicas,
		"ready", sts.Status.ReadyReplicas,
		"target", replicas)
	
	return false, nil
}

// verifyPodsHealthy verifies all pods of a component are healthy
func (m *Manager) verifyPodsHealthy(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, componentName string, replicas int32) (bool, error) {
	podList := &corev1.PodList{}
	labels := client.MatchingLabels{
		"app":       "ozone",
		"component": componentName,
		"cluster":   cluster.Name,
	}
	
	if err := m.client.List(ctx, podList, labels, client.InNamespace(cluster.Namespace)); err != nil {
		return false, err
	}

	healthyPods := 0
	for _, pod := range podList.Items {
		if pod.Status.Phase == corev1.PodRunning && isPodReady(&pod) {
			healthyPods++
		}
	}

	if healthyPods == int(replicas) {
		// Additional health check based on component type
		return m.performComponentHealthCheck(ctx, cluster, componentName)
	}

	m.logger.Info("Waiting for pods to be healthy", 
		"component", componentName,
		"healthy", healthyPods,
		"target", replicas)
	
	return false, nil
}

// performComponentHealthCheck performs component-specific health checks
func (m *Manager) performComponentHealthCheck(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, componentName string) (bool, error) {
	switch componentName {
	case "scm":
		return m.checkSCMHealth(ctx, cluster)
	case "om":
		return m.checkOMHealth(ctx, cluster)
	case "datanode":
		return m.checkDatanodeHealth(ctx, cluster)
	default:
		// For other components, basic pod health is sufficient
		return true, nil
	}
}

// checkSCMHealth checks if SCM is healthy
func (m *Manager) checkSCMHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// In a real implementation, this would check SCM metrics or API
	// For now, we'll simulate a health check with a delay
	time.Sleep(5 * time.Second)
	return true, nil
}

// checkOMHealth checks if OM is healthy
func (m *Manager) checkOMHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// In a real implementation, this would check OM metrics or API
	// For now, we'll simulate a health check with a delay
	time.Sleep(5 * time.Second)
	return true, nil
}

// checkDatanodeHealth checks if Datanodes are healthy
func (m *Manager) checkDatanodeHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// In a real implementation, this would check datanode registration with SCM
	// For now, we'll simulate a health check with a delay
	time.Sleep(5 * time.Second)
	return true, nil
}

// isPodReady checks if a pod is ready
func isPodReady(pod *corev1.Pod) bool {
	for _, condition := range pod.Status.Conditions {
		if condition.Type == corev1.PodReady {
			return condition.Status == corev1.ConditionTrue
		}
	}
	return false
}