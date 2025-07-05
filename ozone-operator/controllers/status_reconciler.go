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

	appsv1 "k8s.io/api/apps/v1"
	"k8s.io/apimachinery/pkg/types"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

func (r *OzoneClusterReconciler) updateComponentStatus(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	// Update SCM status
	scmStatus, err := r.getComponentStatus(ctx, cluster, "scm", cluster.Spec.SCM.Replicas)
	if err != nil {
		return err
	}
	cluster.Status.Components.SCM = scmStatus

	// Update OM status
	omStatus, err := r.getComponentStatus(ctx, cluster, "om", cluster.Spec.OM.Replicas)
	if err != nil {
		return err
	}
	cluster.Status.Components.OM = omStatus

	// Update Datanode status
	dnStatus, err := r.getComponentStatus(ctx, cluster, "datanode", cluster.Spec.Datanodes.Replicas)
	if err != nil {
		return err
	}
	cluster.Status.Components.Datanodes = dnStatus

	// Update S3Gateway status if enabled
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		s3gStatus, err := r.getComponentStatus(ctx, cluster, "s3g", cluster.Spec.S3Gateway.Replicas)
		if err != nil {
			return err
		}
		cluster.Status.Components.S3Gateway = s3gStatus
	}

	// Update Recon status if enabled
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		reconStatus, err := r.getComponentStatus(ctx, cluster, "recon", 1)
		if err != nil {
			return err
		}
		cluster.Status.Components.Recon = reconStatus
	}

	// Update overall ready status
	cluster.Status.Ready = r.isClusterReady(cluster)

	// Update conditions
	r.updateConditions(cluster)

	return nil
}

func (r *OzoneClusterReconciler) getComponentStatus(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, component string, desiredReplicas int32) (ozonev1alpha1.ComponentStatus, error) {
	sts := &appsv1.StatefulSet{}
	err := r.Get(ctx, types.NamespacedName{
		Name:      fmt.Sprintf("%s-%s", cluster.Name, component),
		Namespace: cluster.Namespace,
	}, sts)
	
	status := ozonev1alpha1.ComponentStatus{
		DesiredReplicas: desiredReplicas,
		CurrentVersion:  cluster.Status.Version,
		TargetVersion:   cluster.Spec.Version,
		LastUpdated:     &metav1.Time{Time: metav1.Now().Time},
	}

	if err != nil {
		status.Ready = false
		status.ReadyReplicas = 0
		return status, nil
	}

	status.ReadyReplicas = sts.Status.ReadyReplicas
	status.Ready = sts.Status.ReadyReplicas == desiredReplicas

	return status, nil
}

func (r *OzoneClusterReconciler) isClusterReady(cluster *ozonev1alpha1.OzoneCluster) bool {
	// Check core components
	if !cluster.Status.Components.SCM.Ready ||
		!cluster.Status.Components.OM.Ready ||
		!cluster.Status.Components.Datanodes.Ready {
		return false
	}

	// Check optional components
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled && !cluster.Status.Components.S3Gateway.Ready {
		return false
	}

	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled && !cluster.Status.Components.Recon.Ready {
		return false
	}

	return true
}

func (r *OzoneClusterReconciler) updateConditions(cluster *ozonev1alpha1.OzoneCluster) {
	now := metav1.Now()

	// Available condition
	availableCondition := metav1.Condition{
		Type:               "Available",
		Status:             metav1.ConditionFalse,
		LastTransitionTime: now,
		Reason:             "ClusterNotReady",
		Message:            "Cluster is not ready",
	}

	if cluster.Status.Ready {
		availableCondition.Status = metav1.ConditionTrue
		availableCondition.Reason = "ClusterReady"
		availableCondition.Message = "All components are ready"
	}

	setCondition(&cluster.Status.Conditions, availableCondition)

	// Progressing condition
	progressingCondition := metav1.Condition{
		Type:               "Progressing",
		Status:             metav1.ConditionFalse,
		LastTransitionTime: now,
		Reason:             "ClusterStable",
		Message:            "Cluster is stable",
	}

	if cluster.Status.Phase == ozonev1alpha1.ClusterPhaseInitializing ||
		cluster.Status.Phase == ozonev1alpha1.ClusterPhaseUpgrading {
		progressingCondition.Status = metav1.ConditionTrue
		progressingCondition.Reason = "ClusterProgressing"
		progressingCondition.Message = fmt.Sprintf("Cluster is %s", cluster.Status.Phase)
	}

	setCondition(&cluster.Status.Conditions, progressingCondition)

	// Degraded condition
	degradedCondition := metav1.Condition{
		Type:               "Degraded",
		Status:             metav1.ConditionFalse,
		LastTransitionTime: now,
		Reason:             "ClusterHealthy",
		Message:            "Cluster is healthy",
	}

	if cluster.Status.Phase == ozonev1alpha1.ClusterPhaseFailed {
		degradedCondition.Status = metav1.ConditionTrue
		degradedCondition.Reason = "ClusterFailed"
		degradedCondition.Message = "Cluster is in failed state"
	}

	setCondition(&cluster.Status.Conditions, degradedCondition)
}

func setCondition(conditions *[]metav1.Condition, newCondition metav1.Condition) {
	for i, condition := range *conditions {
		if condition.Type == newCondition.Type {
			if condition.Status != newCondition.Status {
				(*conditions)[i] = newCondition
			}
			return
		}
	}
	*conditions = append(*conditions, newCondition)
}