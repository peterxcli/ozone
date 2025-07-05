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
	"time"

	"github.com/go-logr/logr"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
	"github.com/apache/ozone-operator/pkg/backup"
	"github.com/apache/ozone-operator/pkg/health"
	"github.com/apache/ozone-operator/pkg/upgrade"
)

// OzoneClusterReconciler reconciles a OzoneCluster object
type OzoneClusterReconciler struct {
	client.Client
	Scheme          *runtime.Scheme
	BackupManager   *backup.Manager
	HealthChecker   *health.Checker
	UpgradeManager  *upgrade.Manager
}

//+kubebuilder:rbac:groups=ozone.apache.org,resources=ozoneclusters,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=ozone.apache.org,resources=ozoneclusters/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=ozone.apache.org,resources=ozoneclusters/finalizers,verbs=update
//+kubebuilder:rbac:groups=apps,resources=statefulsets,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=core,resources=services,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=core,resources=configmaps,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=core,resources=secrets,verbs=get;list;watch
//+kubebuilder:rbac:groups=core,resources=persistentvolumeclaims,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=core,resources=pods,verbs=get;list;watch
//+kubebuilder:rbac:groups=batch,resources=cronjobs,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=monitoring.coreos.com,resources=servicemonitors,verbs=get;list;watch;create;update;patch;delete

func (r *OzoneClusterReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	// Fetch the OzoneCluster instance
	ozoneCluster := &ozonev1alpha1.OzoneCluster{}
	err := r.Get(ctx, req.NamespacedName, ozoneCluster)
	if err != nil {
		if errors.IsNotFound(err) {
			logger.Info("OzoneCluster resource not found. Ignoring since object must be deleted")
			return ctrl.Result{}, nil
		}
		logger.Error(err, "Failed to get OzoneCluster")
		return ctrl.Result{}, err
	}

	// Add finalizer for cleanup
	if !controllerutil.ContainsFinalizer(ozoneCluster, "ozone.apache.org/finalizer") {
		controllerutil.AddFinalizer(ozoneCluster, "ozone.apache.org/finalizer")
		if err := r.Update(ctx, ozoneCluster); err != nil {
			return ctrl.Result{}, err
		}
	}

	// Handle deletion
	if !ozoneCluster.DeletionTimestamp.IsZero() {
		return r.handleDeletion(ctx, ozoneCluster, logger)
	}

	// Update status phase
	oldPhase := ozoneCluster.Status.Phase
	r.updatePhase(ozoneCluster)

	// Reconcile based on phase
	switch ozoneCluster.Status.Phase {
	case ozonev1alpha1.ClusterPhasePending:
		return r.reconcilePending(ctx, ozoneCluster, logger)
	case ozonev1alpha1.ClusterPhaseInitializing:
		return r.reconcileInitializing(ctx, ozoneCluster, logger)
	case ozonev1alpha1.ClusterPhaseRunning:
		return r.reconcileRunning(ctx, ozoneCluster, logger)
	case ozonev1alpha1.ClusterPhaseUpgrading:
		return r.reconcileUpgrading(ctx, ozoneCluster, logger)
	case ozonev1alpha1.ClusterPhaseFailed:
		return r.reconcileFailed(ctx, ozoneCluster, logger)
	}

	// Update status if phase changed
	if oldPhase != ozoneCluster.Status.Phase {
		if err := r.Status().Update(ctx, ozoneCluster); err != nil {
			logger.Error(err, "Failed to update OzoneCluster status")
			return ctrl.Result{}, err
		}
	}

	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *OzoneClusterReconciler) reconcilePending(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Reconciling pending cluster")

	// Create ConfigMap
	if err := r.reconcileConfigMap(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Create Services
	if err := r.reconcileServices(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Update phase to Initializing
	cluster.Status.Phase = ozonev1alpha1.ClusterPhaseInitializing
	return ctrl.Result{Requeue: true}, nil
}

func (r *OzoneClusterReconciler) reconcileInitializing(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Reconciling initializing cluster")

	// Create SCM StatefulSet
	if err := r.reconcileSCM(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Wait for SCM to be ready
	scmReady, err := r.isSCMReady(ctx, cluster)
	if err != nil {
		return ctrl.Result{}, err
	}
	if !scmReady {
		logger.Info("Waiting for SCM to be ready")
		return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
	}

	// Create OM StatefulSet
	if err := r.reconcileOM(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Wait for OM to be ready
	omReady, err := r.isOMReady(ctx, cluster)
	if err != nil {
		return ctrl.Result{}, err
	}
	if !omReady {
		logger.Info("Waiting for OM to be ready")
		return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
	}

	// Create Datanodes StatefulSet
	if err := r.reconcileDatanodes(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Create optional components
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		if err := r.reconcileS3Gateway(ctx, cluster, logger); err != nil {
			return ctrl.Result{}, err
		}
	}

	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		if err := r.reconcileRecon(ctx, cluster, logger); err != nil {
			return ctrl.Result{}, err
		}
	}

	// Update phase to Running
	cluster.Status.Phase = ozonev1alpha1.ClusterPhaseRunning
	cluster.Status.Version = cluster.Spec.Version
	return ctrl.Result{Requeue: true}, nil
}

func (r *OzoneClusterReconciler) reconcileRunning(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Reconciling running cluster")

	// Check if upgrade is needed
	if cluster.Status.Version != cluster.Spec.Version {
		logger.Info("Upgrade detected", "current", cluster.Status.Version, "target", cluster.Spec.Version)
		cluster.Status.Phase = ozonev1alpha1.ClusterPhaseUpgrading
		return ctrl.Result{Requeue: true}, nil
	}

	// Reconcile all components
	if err := r.reconcileAllComponents(ctx, cluster, logger); err != nil {
		return ctrl.Result{}, err
	}

	// Update component status
	if err := r.updateComponentStatus(ctx, cluster); err != nil {
		return ctrl.Result{}, err
	}

	// Run health checks
	if r.HealthChecker != nil {
		healthy, err := r.HealthChecker.CheckCluster(ctx, cluster)
		if err != nil {
			logger.Error(err, "Health check failed")
		}
		cluster.Status.Ready = healthy
	}

	// Handle backup if enabled
	if cluster.Spec.Backup != nil && cluster.Spec.Backup.Enabled && r.BackupManager != nil {
		if err := r.BackupManager.ReconcileBackup(ctx, cluster); err != nil {
			logger.Error(err, "Failed to reconcile backup")
		}
	}

	// Handle monitoring if enabled
	if cluster.Spec.Monitoring != nil && cluster.Spec.Monitoring.Enabled {
		if err := r.reconcileMonitoring(ctx, cluster, logger); err != nil {
			logger.Error(err, "Failed to reconcile monitoring")
		}
	}

	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *OzoneClusterReconciler) reconcileUpgrading(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Reconciling upgrading cluster")

	if r.UpgradeManager == nil {
		return ctrl.Result{}, fmt.Errorf("upgrade manager not initialized")
	}

	// Perform rolling upgrade
	completed, err := r.UpgradeManager.UpgradeCluster(ctx, cluster)
	if err != nil {
		logger.Error(err, "Upgrade failed")
		cluster.Status.Phase = ozonev1alpha1.ClusterPhaseFailed
		return ctrl.Result{}, err
	}

	if completed {
		logger.Info("Upgrade completed successfully")
		cluster.Status.Phase = ozonev1alpha1.ClusterPhaseRunning
		cluster.Status.Version = cluster.Spec.Version
		return ctrl.Result{Requeue: true}, nil
	}

	return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
}

func (r *OzoneClusterReconciler) reconcileFailed(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Reconciling failed cluster")

	// Attempt recovery
	if r.HealthChecker != nil {
		if err := r.HealthChecker.AttemptRecovery(ctx, cluster); err != nil {
			logger.Error(err, "Recovery attempt failed")
			return ctrl.Result{RequeueAfter: 60 * time.Second}, nil
		}
	}

	// If recovery successful, transition to Running
	cluster.Status.Phase = ozonev1alpha1.ClusterPhaseRunning
	return ctrl.Result{Requeue: true}, nil
}

func (r *OzoneClusterReconciler) handleDeletion(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) (ctrl.Result, error) {
	logger.Info("Handling deletion")

	// Clean up resources in reverse order
	// Delete Recon
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		if err := r.deleteStatefulSet(ctx, cluster, "recon"); err != nil {
			return ctrl.Result{}, err
		}
	}

	// Delete S3Gateway
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		if err := r.deleteStatefulSet(ctx, cluster, "s3g"); err != nil {
			return ctrl.Result{}, err
		}
	}

	// Delete Datanodes
	if err := r.deleteStatefulSet(ctx, cluster, "datanode"); err != nil {
		return ctrl.Result{}, err
	}

	// Delete OM
	if err := r.deleteStatefulSet(ctx, cluster, "om"); err != nil {
		return ctrl.Result{}, err
	}

	// Delete SCM
	if err := r.deleteStatefulSet(ctx, cluster, "scm"); err != nil {
		return ctrl.Result{}, err
	}

	// Delete Services
	if err := r.deleteServices(ctx, cluster); err != nil {
		return ctrl.Result{}, err
	}

	// Delete ConfigMap
	if err := r.deleteConfigMap(ctx, cluster); err != nil {
		return ctrl.Result{}, err
	}

	// Remove finalizer
	controllerutil.RemoveFinalizer(cluster, "ozone.apache.org/finalizer")
	if err := r.Update(ctx, cluster); err != nil {
		return ctrl.Result{}, err
	}

	return ctrl.Result{}, nil
}

func (r *OzoneClusterReconciler) updatePhase(cluster *ozonev1alpha1.OzoneCluster) {
	if cluster.Status.Phase == "" {
		cluster.Status.Phase = ozonev1alpha1.ClusterPhasePending
	}
	cluster.Status.ObservedGeneration = cluster.Generation
}

func (r *OzoneClusterReconciler) reconcileAllComponents(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	// Reconcile SCM
	if err := r.reconcileSCM(ctx, cluster, logger); err != nil {
		return err
	}

	// Reconcile OM
	if err := r.reconcileOM(ctx, cluster, logger); err != nil {
		return err
	}

	// Reconcile Datanodes
	if err := r.reconcileDatanodes(ctx, cluster, logger); err != nil {
		return err
	}

	// Reconcile S3Gateway if enabled
	if cluster.Spec.S3Gateway != nil && cluster.Spec.S3Gateway.Enabled {
		if err := r.reconcileS3Gateway(ctx, cluster, logger); err != nil {
			return err
		}
	}

	// Reconcile Recon if enabled
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		if err := r.reconcileRecon(ctx, cluster, logger); err != nil {
			return err
		}
	}

	return nil
}

func (r *OzoneClusterReconciler) deleteStatefulSet(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, name string) error {
	ss := &appsv1.StatefulSet{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-%s", cluster.Name, name),
			Namespace: cluster.Namespace,
		},
	}
	return client.IgnoreNotFound(r.Delete(ctx, ss))
}

func (r *OzoneClusterReconciler) deleteServices(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	services := []string{"scm", "om", "datanode", "s3g", "recon"}
	for _, svc := range services {
		service := &corev1.Service{
			ObjectMeta: metav1.ObjectMeta{
				Name:      fmt.Sprintf("%s-%s", cluster.Name, svc),
				Namespace: cluster.Namespace,
			},
		}
		if err := client.IgnoreNotFound(r.Delete(ctx, service)); err != nil {
			return err
		}
	}
	return nil
}

func (r *OzoneClusterReconciler) deleteConfigMap(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-config", cluster.Name),
			Namespace: cluster.Namespace,
		},
	}
	return client.IgnoreNotFound(r.Delete(ctx, cm))
}

// SetupWithManager sets up the controller with the Manager.
func (r *OzoneClusterReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&ozonev1alpha1.OzoneCluster{}).
		Owns(&appsv1.StatefulSet{}).
		Owns(&corev1.Service{}).
		Owns(&corev1.ConfigMap{}).
		Complete(r)
}