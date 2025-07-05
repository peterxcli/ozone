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

package v1alpha1

import (
	"fmt"

	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/webhook"
)

// log is for logging in this package.
var ozoneclusterlog = logf.Log.WithName("ozonecluster-resource")

func (r *OzoneCluster) SetupWebhookWithManager(mgr ctrl.Manager) error {
	return ctrl.NewWebhookManagedBy(mgr).
		For(r).
		Complete()
}

//+kubebuilder:webhook:path=/mutate-ozone-apache-org-v1alpha1-ozonecluster,mutating=true,failurePolicy=fail,sideEffects=None,groups=ozone.apache.org,resources=ozoneclusters,verbs=create;update,versions=v1alpha1,name=mozonecluster.kb.io,admissionReviewVersions=v1

var _ webhook.Defaulter = &OzoneCluster{}

// Default implements webhook.Defaulter so a webhook will be registered for the type
func (r *OzoneCluster) Default() {
	ozoneclusterlog.Info("default", "name", r.Name)

	// Set default image if not specified
	if r.Spec.Image == "" {
		r.Spec.Image = fmt.Sprintf("apache/ozone:%s", r.Spec.Version)
	}

	// Set default resources if not specified
	if r.Spec.SCM.Resources.Requests == nil {
		r.Spec.SCM.Resources = DefaultSCMResources()
	}
	if r.Spec.OM.Resources.Requests == nil {
		r.Spec.OM.Resources = DefaultOMResources()
	}
	if r.Spec.Datanodes.Resources.Requests == nil {
		r.Spec.Datanodes.Resources = DefaultDatanodeResources()
	}

	// Set default S3Gateway resources if enabled
	if r.Spec.S3Gateway != nil && r.Spec.S3Gateway.Enabled && r.Spec.S3Gateway.Resources.Requests == nil {
		r.Spec.S3Gateway.Resources = DefaultS3GatewayResources()
	}

	// Set default Recon resources if enabled
	if r.Spec.Recon != nil && r.Spec.Recon.Enabled && r.Spec.Recon.Resources.Requests == nil {
		r.Spec.Recon.Resources = DefaultReconResources()
	}
}

//+kubebuilder:webhook:path=/validate-ozone-apache-org-v1alpha1-ozonecluster,mutating=false,failurePolicy=fail,sideEffects=None,groups=ozone.apache.org,resources=ozoneclusters,verbs=create;update,versions=v1alpha1,name=vozonecluster.kb.io,admissionReviewVersions=v1

var _ webhook.Validator = &OzoneCluster{}

// ValidateCreate implements webhook.Validator so a webhook will be registered for the type
func (r *OzoneCluster) ValidateCreate() error {
	ozoneclusterlog.Info("validate create", "name", r.Name)

	if err := r.validateCluster(); err != nil {
		return err
	}

	return nil
}

// ValidateUpdate implements webhook.Validator so a webhook will be registered for the type
func (r *OzoneCluster) ValidateUpdate(old runtime.Object) error {
	ozoneclusterlog.Info("validate update", "name", r.Name)

	if err := r.validateCluster(); err != nil {
		return err
	}

	// Validate version downgrade
	oldCluster := old.(*OzoneCluster)
	if r.Spec.Version < oldCluster.Spec.Version {
		return fmt.Errorf("downgrading from version %s to %s is not supported", oldCluster.Spec.Version, r.Spec.Version)
	}

	return nil
}

// ValidateDelete implements webhook.Validator so a webhook will be registered for the type
func (r *OzoneCluster) ValidateDelete() error {
	ozoneclusterlog.Info("validate delete", "name", r.Name)
	return nil
}

func (r *OzoneCluster) validateCluster() error {
	// Validate HA configuration
	if r.Spec.SCM.EnableHA && r.Spec.SCM.Replicas < 3 {
		return fmt.Errorf("SCM HA requires at least 3 replicas, got %d", r.Spec.SCM.Replicas)
	}

	if r.Spec.OM.EnableHA && r.Spec.OM.Replicas < 3 {
		return fmt.Errorf("OM HA requires at least 3 replicas, got %d", r.Spec.OM.Replicas)
	}

	// Validate datanode configuration
	if len(r.Spec.Datanodes.DataVolumes) == 0 {
		return fmt.Errorf("at least one data volume must be specified for datanodes")
	}

	// Validate backup configuration
	if r.Spec.Backup != nil && r.Spec.Backup.Enabled {
		if r.Spec.Backup.Destination == "" {
			return fmt.Errorf("backup destination must be specified when backup is enabled")
		}
	}

	return nil
}