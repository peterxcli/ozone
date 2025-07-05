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

package health

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/go-logr/logr"
	corev1 "k8s.io/api/core/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

// Checker performs health checks on Ozone cluster components
type Checker struct {
	client     client.Client
	logger     logr.Logger
	httpClient *http.Client
}

// NewChecker creates a new health checker
func NewChecker(client client.Client, logger logr.Logger) *Checker {
	return &Checker{
		client: client,
		logger: logger,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// CheckCluster performs health checks on all cluster components
func (c *Checker) CheckCluster(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	c.logger.Info("Performing cluster health check", "cluster", cluster.Name)

	// Check SCM health
	scmHealthy, err := c.checkSCMHealth(ctx, cluster)
	if err != nil {
		c.logger.Error(err, "SCM health check failed")
		return false, err
	}
	if !scmHealthy {
		c.logger.Info("SCM is not healthy")
		return false, nil
	}

	// Check OM health
	omHealthy, err := c.checkOMHealth(ctx, cluster)
	if err != nil {
		c.logger.Error(err, "OM health check failed")
		return false, err
	}
	if !omHealthy {
		c.logger.Info("OM is not healthy")
		return false, nil
	}

	// Check Datanode health
	dnHealthy, err := c.checkDatanodeHealth(ctx, cluster)
	if err != nil {
		c.logger.Error(err, "Datanode health check failed")
		return false, err
	}
	if !dnHealthy {
		c.logger.Info("Datanodes are not healthy")
		return false, nil
	}

	// Check optional components
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		s3gHealthy, err := c.checkS3GatewayHealth(ctx, cluster)
		if err != nil {
			c.logger.Error(err, "S3Gateway health check failed")
			return false, err
		}
		if !s3gHealthy {
			c.logger.Info("S3Gateway is not healthy")
			return false, nil
		}
	}

	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		reconHealthy, err := c.checkReconHealth(ctx, cluster)
		if err != nil {
			c.logger.Error(err, "Recon health check failed")
			return false, err
		}
		if !reconHealthy {
			c.logger.Info("Recon is not healthy")
			return false, nil
		}
	}

	c.logger.Info("Cluster is healthy", "cluster", cluster.Name)
	return true, nil
}

// AttemptRecovery attempts to recover unhealthy components
func (c *Checker) AttemptRecovery(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	c.logger.Info("Attempting cluster recovery", "cluster", cluster.Name)

	// Check and recover each component
	components := []string{"scm", "om", "datanode"}
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		components = append(components, "s3g")
	}
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		components = append(components, "recon")
	}

	for _, component := range components {
		if err := c.recoverComponent(ctx, cluster, component); err != nil {
			c.logger.Error(err, "Failed to recover component", "component", component)
			return err
		}
	}

	return nil
}

// checkSCMHealth checks SCM health
func (c *Checker) checkSCMHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// Check pod health
	healthy, err := c.checkComponentPods(ctx, cluster, "scm", cluster.Spec.SCM.Replicas)
	if err != nil || !healthy {
		return healthy, err
	}

	// Check SCM metrics endpoint
	for i := int32(0); i < cluster.Spec.SCM.Replicas; i++ {
		url := fmt.Sprintf("http://%s-scm-%d.%s-scm.%s.svc.cluster.local:9876/prom", 
			cluster.Name, i, cluster.Name, cluster.Namespace)
		
		if err := c.checkHTTPEndpoint(url); err != nil {
			c.logger.Error(err, "SCM metrics endpoint check failed", "pod", i)
			return false, nil
		}
	}

	return true, nil
}

// checkOMHealth checks OM health
func (c *Checker) checkOMHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// Check pod health
	healthy, err := c.checkComponentPods(ctx, cluster, "om", cluster.Spec.OM.Replicas)
	if err != nil || !healthy {
		return healthy, err
	}

	// Check OM metrics endpoint
	for i := int32(0); i < cluster.Spec.OM.Replicas; i++ {
		url := fmt.Sprintf("http://%s-om-%d.%s-om.%s.svc.cluster.local:9874/prom", 
			cluster.Name, i, cluster.Name, cluster.Namespace)
		
		if err := c.checkHTTPEndpoint(url); err != nil {
			c.logger.Error(err, "OM metrics endpoint check failed", "pod", i)
			return false, nil
		}
	}

	return true, nil
}

// checkDatanodeHealth checks Datanode health
func (c *Checker) checkDatanodeHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// Check pod health
	healthy, err := c.checkComponentPods(ctx, cluster, "datanode", cluster.Spec.Datanodes.Replicas)
	if err != nil || !healthy {
		return healthy, err
	}

	// In production, we would check if datanodes are registered with SCM
	// For now, just check metrics endpoints
	for i := int32(0); i < cluster.Spec.Datanodes.Replicas; i++ {
		url := fmt.Sprintf("http://%s-datanode-%d.%s-datanode.%s.svc.cluster.local:9882/prom", 
			cluster.Name, i, cluster.Name, cluster.Namespace)
		
		if err := c.checkHTTPEndpoint(url); err != nil {
			c.logger.Error(err, "Datanode metrics endpoint check failed", "pod", i)
			return false, nil
		}
	}

	return true, nil
}

// checkS3GatewayHealth checks S3Gateway health
func (c *Checker) checkS3GatewayHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// Check pod health
	healthy, err := c.checkComponentPods(ctx, cluster, "s3g", cluster.Spec.S3Gateway.Replicas)
	if err != nil || !healthy {
		return healthy, err
	}

	// Check S3 endpoint
	for i := int32(0); i < cluster.Spec.S3Gateway.Replicas; i++ {
		url := fmt.Sprintf("http://%s-s3g-%d.%s-s3g.%s.svc.cluster.local:9878/", 
			cluster.Name, i, cluster.Name, cluster.Namespace)
		
		if err := c.checkHTTPEndpoint(url); err != nil {
			c.logger.Error(err, "S3Gateway endpoint check failed", "pod", i)
			return false, nil
		}
	}

	return true, nil
}

// checkReconHealth checks Recon health
func (c *Checker) checkReconHealth(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) (bool, error) {
	// Check pod health
	healthy, err := c.checkComponentPods(ctx, cluster, "recon", 1)
	if err != nil || !healthy {
		return healthy, err
	}

	// Check Recon API endpoint
	url := fmt.Sprintf("http://%s-recon-0.%s-recon.%s.svc.cluster.local:9888/api/v1/task/status", 
		cluster.Name, cluster.Name, cluster.Namespace)
	
	if err := c.checkHTTPEndpoint(url); err != nil {
		c.logger.Error(err, "Recon API endpoint check failed")
		return false, nil
	}

	return true, nil
}

// checkComponentPods checks if component pods are healthy
func (c *Checker) checkComponentPods(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, component string, replicas int32) (bool, error) {
	podList := &corev1.PodList{}
	labels := client.MatchingLabels{
		"app":       "ozone",
		"component": component,
		"cluster":   cluster.Name,
	}
	
	if err := c.client.List(ctx, podList, labels, client.InNamespace(cluster.Namespace)); err != nil {
		return false, err
	}

	healthyPods := 0
	for _, pod := range podList.Items {
		if isPodHealthy(&pod) {
			healthyPods++
		}
	}

	if healthyPods != int(replicas) {
		c.logger.Info("Component pods not healthy", 
			"component", component,
			"healthy", healthyPods,
			"expected", replicas)
		return false, nil
	}

	return true, nil
}

// checkHTTPEndpoint checks if an HTTP endpoint is reachable
func (c *Checker) checkHTTPEndpoint(url string) error {
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("endpoint returned status %d", resp.StatusCode)
	}

	return nil
}

// recoverComponent attempts to recover an unhealthy component
func (c *Checker) recoverComponent(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, component string) error {
	c.logger.Info("Recovering component", "component", component)

	// Find unhealthy pods
	podList := &corev1.PodList{}
	labels := client.MatchingLabels{
		"app":       "ozone",
		"component": component,
		"cluster":   cluster.Name,
	}
	
	if err := c.client.List(ctx, podList, labels, client.InNamespace(cluster.Namespace)); err != nil {
		return err
	}

	for _, pod := range podList.Items {
		if !isPodHealthy(&pod) {
			c.logger.Info("Deleting unhealthy pod", "pod", pod.Name)
			// Delete the pod to trigger recreation
			if err := c.client.Delete(ctx, &pod); err != nil {
				return err
			}
		}
	}

	return nil
}

// isPodHealthy checks if a pod is healthy
func isPodHealthy(pod *corev1.Pod) bool {
	if pod.Status.Phase != corev1.PodRunning {
		return false
	}

	// Check if all containers are ready
	for _, containerStatus := range pod.Status.ContainerStatuses {
		if !containerStatus.Ready {
			return false
		}
		
		// Check for recent restarts
		if containerStatus.RestartCount > 3 {
			return false
		}
	}

	// Check pod conditions
	for _, condition := range pod.Status.Conditions {
		if condition.Type == corev1.PodReady {
			return condition.Status == corev1.ConditionTrue
		}
	}

	return false
}