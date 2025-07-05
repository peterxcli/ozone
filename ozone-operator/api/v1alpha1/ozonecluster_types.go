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
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// OzoneClusterSpec defines the desired state of OzoneCluster
type OzoneClusterSpec struct {
	// Version is the Ozone version to deploy
	Version string `json:"version"`

	// Image is the container image to use
	// +optional
	Image string `json:"image,omitempty"`

	// ImagePullPolicy for all containers
	// +optional
	ImagePullPolicy corev1.PullPolicy `json:"imagePullPolicy,omitempty"`

	// ImagePullSecrets for pulling images from private registries
	// +optional
	ImagePullSecrets []corev1.LocalObjectReference `json:"imagePullSecrets,omitempty"`

	// SCM defines the Storage Container Manager configuration
	SCM SCMSpec `json:"scm"`

	// OM defines the Ozone Manager configuration
	OM OMSpec `json:"om"`

	// Datanodes defines the datanode configuration
	Datanodes DatanodeSpec `json:"datanodes"`

	// S3Gateway defines the S3 gateway configuration
	// +optional
	S3Gateway *S3GatewaySpec `json:"s3Gateway,omitempty"`

	// Recon defines the Recon service configuration
	// +optional
	Recon *ReconSpec `json:"recon,omitempty"`

	// Security defines security configurations
	// +optional
	Security *SecuritySpec `json:"security,omitempty"`

	// Monitoring defines monitoring configurations
	// +optional
	Monitoring *MonitoringSpec `json:"monitoring,omitempty"`

	// Backup defines backup configurations
	// +optional
	Backup *BackupSpec `json:"backup,omitempty"`

	// ConfigOverrides allows overriding specific Ozone configurations
	// +optional
	ConfigOverrides map[string]string `json:"configOverrides,omitempty"`
}

// SCMSpec defines Storage Container Manager configuration
type SCMSpec struct {
	// Replicas is the number of SCM instances
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:default=3
	Replicas int32 `json:"replicas,omitempty"`

	// Resources defines resource requirements
	// +optional
	Resources corev1.ResourceRequirements `json:"resources,omitempty"`

	// StorageSize for metadata
	// +kubebuilder:default="10Gi"
	StorageSize resource.Quantity `json:"storageSize,omitempty"`

	// StorageClass for metadata PVC
	// +optional
	StorageClass *string `json:"storageClass,omitempty"`

	// EnableHA enables high availability mode
	// +kubebuilder:default=true
	EnableHA bool `json:"enableHA,omitempty"`

	// NodeSelector for pod placement
	// +optional
	NodeSelector map[string]string `json:"nodeSelector,omitempty"`

	// Affinity rules for pod placement
	// +optional
	Affinity *corev1.Affinity `json:"affinity,omitempty"`

	// Tolerations for pod placement
	// +optional
	Tolerations []corev1.Toleration `json:"tolerations,omitempty"`
}

// OMSpec defines Ozone Manager configuration
type OMSpec struct {
	// Replicas is the number of OM instances
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:default=3
	Replicas int32 `json:"replicas,omitempty"`

	// Resources defines resource requirements
	// +optional
	Resources corev1.ResourceRequirements `json:"resources,omitempty"`

	// StorageSize for metadata
	// +kubebuilder:default="10Gi"
	StorageSize resource.Quantity `json:"storageSize,omitempty"`

	// StorageClass for metadata PVC
	// +optional
	StorageClass *string `json:"storageClass,omitempty"`

	// EnableHA enables high availability mode
	// +kubebuilder:default=true
	EnableHA bool `json:"enableHA,omitempty"`

	// NodeSelector for pod placement
	// +optional
	NodeSelector map[string]string `json:"nodeSelector,omitempty"`

	// Affinity rules for pod placement
	// +optional
	Affinity *corev1.Affinity `json:"affinity,omitempty"`

	// Tolerations for pod placement
	// +optional
	Tolerations []corev1.Toleration `json:"tolerations,omitempty"`
}

// DatanodeSpec defines datanode configuration
type DatanodeSpec struct {
	// Replicas is the number of datanode instances
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:default=3
	Replicas int32 `json:"replicas,omitempty"`

	// Resources defines resource requirements
	// +optional
	Resources corev1.ResourceRequirements `json:"resources,omitempty"`

	// DataVolumes defines the data storage volumes
	DataVolumes []DataVolume `json:"dataVolumes"`

	// NodeSelector for pod placement
	// +optional
	NodeSelector map[string]string `json:"nodeSelector,omitempty"`

	// Affinity rules for pod placement
	// +optional
	Affinity *corev1.Affinity `json:"affinity,omitempty"`

	// Tolerations for pod placement
	// +optional
	Tolerations []corev1.Toleration `json:"tolerations,omitempty"`
}

// DataVolume defines a data storage volume
type DataVolume struct {
	// Size of the data volume
	Size resource.Quantity `json:"size"`

	// StorageClass for the data volume
	// +optional
	StorageClass *string `json:"storageClass,omitempty"`

	// MountPath for the volume
	// +optional
	MountPath string `json:"mountPath,omitempty"`
}

// S3GatewaySpec defines S3 gateway configuration
type S3GatewaySpec struct {
	// Enabled determines if S3 gateway should be deployed
	// +kubebuilder:default=false
	Enabled bool `json:"enabled,omitempty"`

	// Replicas is the number of S3 gateway instances
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:default=2
	Replicas int32 `json:"replicas,omitempty"`

	// Resources defines resource requirements
	// +optional
	Resources corev1.ResourceRequirements `json:"resources,omitempty"`

	// ServiceType for S3 gateway service
	// +kubebuilder:default="ClusterIP"
	ServiceType corev1.ServiceType `json:"serviceType,omitempty"`

	// NodeSelector for pod placement
	// +optional
	NodeSelector map[string]string `json:"nodeSelector,omitempty"`
}

// ReconSpec defines Recon service configuration
type ReconSpec struct {
	// Enabled determines if Recon should be deployed
	// +kubebuilder:default=false
	Enabled bool `json:"enabled,omitempty"`

	// Resources defines resource requirements
	// +optional
	Resources corev1.ResourceRequirements `json:"resources,omitempty"`

	// StorageSize for Recon database
	// +kubebuilder:default="20Gi"
	StorageSize resource.Quantity `json:"storageSize,omitempty"`

	// StorageClass for Recon PVC
	// +optional
	StorageClass *string `json:"storageClass,omitempty"`
}

// SecuritySpec defines security configuration
type SecuritySpec struct {
	// Enabled determines if security features are enabled
	// +kubebuilder:default=false
	Enabled bool `json:"enabled,omitempty"`

	// KerberosEnabled enables Kerberos authentication
	// +kubebuilder:default=false
	KerberosEnabled bool `json:"kerberosEnabled,omitempty"`

	// TLSEnabled enables TLS encryption
	// +kubebuilder:default=false
	TLSEnabled bool `json:"tlsEnabled,omitempty"`

	// CertificateSecret references a secret containing certificates
	// +optional
	CertificateSecret *corev1.SecretReference `json:"certificateSecret,omitempty"`

	// KerberosKeytabSecret references a secret containing Kerberos keytab
	// +optional
	KerberosKeytabSecret *corev1.SecretReference `json:"kerberosKeytabSecret,omitempty"`
}

// MonitoringSpec defines monitoring configuration
type MonitoringSpec struct {
	// Enabled determines if monitoring is enabled
	// +kubebuilder:default=false
	Enabled bool `json:"enabled,omitempty"`

	// PrometheusOperator integration
	// +optional
	PrometheusOperator *PrometheusOperatorSpec `json:"prometheusOperator,omitempty"`

	// Grafana dashboard configuration
	// +optional
	GrafanaDashboard *GrafanaDashboardSpec `json:"grafanaDashboard,omitempty"`
}

// PrometheusOperatorSpec defines Prometheus Operator integration
type PrometheusOperatorSpec struct {
	// ServiceMonitor creates ServiceMonitor resources
	// +kubebuilder:default=true
	ServiceMonitor bool `json:"serviceMonitor,omitempty"`

	// Labels to add to ServiceMonitor
	// +optional
	Labels map[string]string `json:"labels,omitempty"`

	// Interval for scraping metrics
	// +kubebuilder:default="30s"
	// +optional
	Interval string `json:"interval,omitempty"`
}

// GrafanaDashboardSpec defines Grafana dashboard configuration
type GrafanaDashboardSpec struct {
	// Enabled determines if Grafana dashboards should be created
	// +kubebuilder:default=true
	Enabled bool `json:"enabled,omitempty"`

	// Labels to add to dashboard ConfigMaps
	// +optional
	Labels map[string]string `json:"labels,omitempty"`
}

// BackupSpec defines backup configuration
type BackupSpec struct {
	// Enabled determines if backup is enabled
	// +kubebuilder:default=false
	Enabled bool `json:"enabled,omitempty"`

	// Schedule in cron format
	// +kubebuilder:default="0 2 * * *"
	Schedule string `json:"schedule,omitempty"`

	// Destination for backups (s3://bucket/path or pvc://claim-name/path)
	Destination string `json:"destination"`

	// Retention policy
	// +optional
	Retention *RetentionPolicy `json:"retention,omitempty"`

	// S3Config for S3 backup destination
	// +optional
	S3Config *S3BackupConfig `json:"s3Config,omitempty"`
}

// RetentionPolicy defines backup retention
type RetentionPolicy struct {
	// Days to keep backups
	// +kubebuilder:default=7
	Days int32 `json:"days,omitempty"`

	// Count of backups to keep
	// +kubebuilder:default=10
	Count int32 `json:"count,omitempty"`
}

// S3BackupConfig defines S3 backup configuration
type S3BackupConfig struct {
	// Endpoint for S3-compatible storage
	Endpoint string `json:"endpoint"`

	// Region for S3
	// +optional
	Region string `json:"region,omitempty"`

	// CredentialsSecret references AWS credentials
	CredentialsSecret corev1.SecretReference `json:"credentialsSecret"`

	// UseSSL for S3 connection
	// +kubebuilder:default=true
	UseSSL bool `json:"useSSL,omitempty"`
}

// OzoneClusterStatus defines the observed state of OzoneCluster
type OzoneClusterStatus struct {
	// Phase represents the current phase of cluster
	Phase ClusterPhase `json:"phase,omitempty"`

	// Conditions represent the latest available observations
	Conditions []metav1.Condition `json:"conditions,omitempty"`

	// Ready indicates if the cluster is ready
	Ready bool `json:"ready,omitempty"`

	// Version is the current running version
	Version string `json:"version,omitempty"`

	// Components status
	Components ComponentsStatus `json:"components,omitempty"`

	// LastBackup timestamp
	// +optional
	LastBackup *metav1.Time `json:"lastBackup,omitempty"`

	// ObservedGeneration is the last observed generation
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`
}

// ClusterPhase represents the phase of the cluster
type ClusterPhase string

const (
	ClusterPhasePending      ClusterPhase = "Pending"
	ClusterPhaseInitializing ClusterPhase = "Initializing"
	ClusterPhaseRunning      ClusterPhase = "Running"
	ClusterPhaseUpgrading    ClusterPhase = "Upgrading"
	ClusterPhaseFailed       ClusterPhase = "Failed"
	ClusterPhaseDeleting     ClusterPhase = "Deleting"
)

// ComponentsStatus represents status of each component
type ComponentsStatus struct {
	SCM       ComponentStatus `json:"scm,omitempty"`
	OM        ComponentStatus `json:"om,omitempty"`
	Datanodes ComponentStatus `json:"datanodes,omitempty"`
	S3Gateway ComponentStatus `json:"s3Gateway,omitempty"`
	Recon     ComponentStatus `json:"recon,omitempty"`
}

// ComponentStatus represents individual component status
type ComponentStatus struct {
	Ready            bool   `json:"ready,omitempty"`
	ReadyReplicas    int32  `json:"readyReplicas,omitempty"`
	DesiredReplicas  int32  `json:"desiredReplicas,omitempty"`
	CurrentVersion   string `json:"currentVersion,omitempty"`
	TargetVersion    string `json:"targetVersion,omitempty"`
	LastUpdated      *metav1.Time `json:"lastUpdated,omitempty"`
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status
//+kubebuilder:resource:shortName=oz
//+kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase",description="Cluster phase"
//+kubebuilder:printcolumn:name="Ready",type="boolean",JSONPath=".status.ready",description="Cluster readiness"
//+kubebuilder:printcolumn:name="Version",type="string",JSONPath=".status.version",description="Ozone version"
//+kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// OzoneCluster is the Schema for the ozoneclusters API
type OzoneCluster struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   OzoneClusterSpec   `json:"spec,omitempty"`
	Status OzoneClusterStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// OzoneClusterList contains a list of OzoneCluster
type OzoneClusterList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []OzoneCluster `json:"items"`
}

func init() {
	SchemeBuilder.Register(&OzoneCluster{}, &OzoneClusterList{})
}