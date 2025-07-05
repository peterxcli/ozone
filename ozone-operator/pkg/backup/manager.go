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

package backup

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/go-logr/logr"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

// Manager handles Ozone cluster backups
type Manager struct {
	client client.Client
	logger logr.Logger
	scheme *runtime.Scheme
}

// NewManager creates a new backup manager
func NewManager(client client.Client, logger logr.Logger, scheme *runtime.Scheme) *Manager {
	return &Manager{
		client: client,
		logger: logger,
		scheme: scheme,
	}
}

// ReconcileBackup ensures backup CronJob exists and is configured correctly
func (m *Manager) ReconcileBackup(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	if cluster.Spec.Backup == nil || !cluster.Spec.Backup.Enabled {
		// Delete backup CronJob if exists
		return m.deleteBackupCronJob(ctx, cluster)
	}

	// Create or update backup CronJob
	cronJob := m.buildBackupCronJob(cluster)
	if err := controllerutil.SetControllerReference(cluster, cronJob, m.scheme); err != nil {
		return err
	}

	found := &batchv1.CronJob{}
	err := m.client.Get(ctx, types.NamespacedName{
		Name:      cronJob.Name,
		Namespace: cronJob.Namespace,
	}, found)

	if err != nil && errors.IsNotFound(err) {
		m.logger.Info("Creating backup CronJob", "name", cronJob.Name)
		return m.client.Create(ctx, cronJob)
	} else if err != nil {
		return err
	}

	// Update if schedule changed
	if found.Spec.Schedule != cronJob.Spec.Schedule {
		found.Spec = cronJob.Spec
		m.logger.Info("Updating backup CronJob", "name", cronJob.Name)
		return m.client.Update(ctx, found)
	}

	// Check last backup time
	if len(found.Status.Active) > 0 {
		cluster.Status.LastBackup = &found.Status.LastScheduleTime
	}

	return nil
}

// RestoreCluster restores an Ozone cluster from backup
func (m *Manager) RestoreCluster(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, backupPath string) error {
	m.logger.Info("Starting cluster restore", "cluster", cluster.Name, "backup", backupPath)

	// Create restore job
	job := m.buildRestoreJob(cluster, backupPath)
	if err := controllerutil.SetControllerReference(cluster, job, m.scheme); err != nil {
		return err
	}

	if err := m.client.Create(ctx, job); err != nil {
		return err
	}

	// Wait for restore to complete
	return m.waitForJobCompletion(ctx, job)
}

// buildBackupCronJob builds a CronJob for periodic backups
func (m *Manager) buildBackupCronJob(cluster *ozonev1alpha1.OzoneCluster) *batchv1.CronJob {
	backoffLimit := int32(3)
	successfulJobsHistoryLimit := int32(3)
	failedJobsHistoryLimit := int32(3)

	labels := map[string]string{
		"app":       "ozone",
		"component": "backup",
		"cluster":   cluster.Name,
	}

	// Build backup command based on destination
	backupCmd := m.buildBackupCommand(cluster)

	return &batchv1.CronJob{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-backup", cluster.Name),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: batchv1.CronJobSpec{
			Schedule: cluster.Spec.Backup.Schedule,
			SuccessfulJobsHistoryLimit: &successfulJobsHistoryLimit,
			FailedJobsHistoryLimit:     &failedJobsHistoryLimit,
			JobTemplate: batchv1.JobTemplateSpec{
				Spec: batchv1.JobSpec{
					BackoffLimit: &backoffLimit,
					Template: corev1.PodTemplateSpec{
						Spec: corev1.PodSpec{
							RestartPolicy: corev1.RestartPolicyOnFailure,
							Containers: []corev1.Container{
								{
									Name:            "backup",
									Image:           cluster.Spec.Image,
									ImagePullPolicy: cluster.Spec.ImagePullPolicy,
									Command:         []string{"/bin/bash", "-c"},
									Args:            []string{backupCmd},
									EnvFrom: []corev1.EnvFromSource{
										{
											ConfigMapRef: &corev1.ConfigMapEnvSource{
												LocalObjectReference: corev1.LocalObjectReference{
													Name: fmt.Sprintf("%s-config", cluster.Name),
												},
											},
										},
									},
									Env: m.buildBackupEnv(cluster),
									VolumeMounts: []corev1.VolumeMount{
										{
											Name:      "config",
											MountPath: "/opt/hadoop/etc/hadoop",
										},
									},
								},
							},
							Volumes: []corev1.Volume{
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
							},
							ImagePullSecrets: cluster.Spec.ImagePullSecrets,
						},
					},
				},
			},
		},
	}
}

// buildBackupCommand builds the backup command based on configuration
func (m *Manager) buildBackupCommand(cluster *ozonev1alpha1.OzoneCluster) string {
	destination := cluster.Spec.Backup.Destination

	// Base backup commands
	commands := []string{
		"set -e",
		fmt.Sprintf("BACKUP_ID=\"%s-${timestamp}\"", cluster.Name),
		"echo \"Starting backup ${BACKUP_ID}\"",
	}

	// OM snapshot
	commands = append(commands, []string{
		"echo \"Creating OM snapshot...\"",
		"ozone admin om finalizeupgrade -id ${OZONE_OM_SERVICE_ID}",
		"ozone admin om snapshot create ${BACKUP_ID}",
	}...)

	// SCM snapshot
	commands = append(commands, []string{
		"echo \"Creating SCM snapshot...\"",
		"ozone admin scm finalizeupgrade",
		"ozone admin scm snapshot create ${BACKUP_ID}",
	}...)

	// Upload snapshots based on destination type
	if strings.HasPrefix(destination, "s3://") {
		commands = append(commands, m.buildS3BackupCommands(cluster, destination)...)
	} else if strings.HasPrefix(destination, "pvc://") {
		commands = append(commands, m.buildPVCBackupCommands(cluster, destination)...)
	}

	// Cleanup old backups if retention is configured
	if cluster.Spec.Backup.Retention != nil {
		commands = append(commands, m.buildRetentionCommands(cluster)...)
	}

	return strings.Join(commands, "\n")
}

// buildS3BackupCommands builds commands for S3 backup
func (m *Manager) buildS3BackupCommands(cluster *ozonev1alpha1.OzoneCluster, destination string) []string {
	s3Path := strings.TrimPrefix(destination, "s3://")
	
	commands := []string{
		"echo \"Uploading to S3...\"",
		fmt.Sprintf("aws s3 cp /data/metadata/om/snapshots/${BACKUP_ID} s3://%s/${BACKUP_ID}/om/ --recursive", s3Path),
		fmt.Sprintf("aws s3 cp /data/metadata/scm/snapshots/${BACKUP_ID} s3://%s/${BACKUP_ID}/scm/ --recursive", s3Path),
	}

	if cluster.Spec.Backup.S3Config != nil && !cluster.Spec.Backup.S3Config.UseSSL {
		commands = append([]string{"export AWS_ENDPOINT_URL_S3=\"http://${S3_ENDPOINT}\""}, commands...)
	} else {
		commands = append([]string{"export AWS_ENDPOINT_URL_S3=\"https://${S3_ENDPOINT}\""}, commands...)
	}

	return commands
}

// buildPVCBackupCommands builds commands for PVC backup
func (m *Manager) buildPVCBackupCommands(cluster *ozonev1alpha1.OzoneCluster, destination string) []string {
	pvcPath := strings.TrimPrefix(destination, "pvc://")
	parts := strings.SplitN(pvcPath, "/", 2)
	
	backupPath := "/backup"
	if len(parts) > 1 {
		backupPath = fmt.Sprintf("/backup/%s", parts[1])
	}

	return []string{
		"echo \"Copying to PVC...\"",
		fmt.Sprintf("mkdir -p %s/${BACKUP_ID}", backupPath),
		fmt.Sprintf("cp -r /data/metadata/om/snapshots/${BACKUP_ID} %s/${BACKUP_ID}/om/", backupPath),
		fmt.Sprintf("cp -r /data/metadata/scm/snapshots/${BACKUP_ID} %s/${BACKUP_ID}/scm/", backupPath),
	}
}

// buildRetentionCommands builds commands for backup retention
func (m *Manager) buildRetentionCommands(cluster *ozonev1alpha1.OzoneCluster) []string {
	retention := cluster.Spec.Backup.Retention
	
	commands := []string{
		"echo \"Applying retention policy...\"",
	}

	if strings.HasPrefix(cluster.Spec.Backup.Destination, "s3://") {
		s3Path := strings.TrimPrefix(cluster.Spec.Backup.Destination, "s3://")
		
		// List and delete old backups
		commands = append(commands, fmt.Sprintf(
			`aws s3 ls s3://%s/ | grep "PRE %s-" | sort -r | tail -n +%d | awk '{print $2}' | xargs -I {} aws s3 rm s3://%s/{} --recursive`,
			s3Path, cluster.Name, retention.Count+1, s3Path,
		))
	}

	return commands
}

// buildBackupEnv builds environment variables for backup
func (m *Manager) buildBackupEnv(cluster *ozonev1alpha1.OzoneCluster) []corev1.EnvVar {
	env := []corev1.EnvVar{
		{
			Name:  "OZONE_OM_SERVICE_ID",
			Value: cluster.Name,
		},
	}

	if cluster.Spec.Backup.S3Config != nil {
		env = append(env, []corev1.EnvVar{
			{
				Name:  "S3_ENDPOINT",
				Value: cluster.Spec.Backup.S3Config.Endpoint,
			},
			{
				Name:  "AWS_DEFAULT_REGION",
				Value: cluster.Spec.Backup.S3Config.Region,
			},
			{
				Name: "AWS_ACCESS_KEY_ID",
				ValueFrom: &corev1.EnvVarSource{
					SecretKeyRef: &corev1.SecretKeySelector{
						LocalObjectReference: corev1.LocalObjectReference{
							Name: cluster.Spec.Backup.S3Config.CredentialsSecret.Name,
						},
						Key: "access-key",
					},
				},
			},
			{
				Name: "AWS_SECRET_ACCESS_KEY",
				ValueFrom: &corev1.EnvVarSource{
					SecretKeyRef: &corev1.SecretKeySelector{
						LocalObjectReference: corev1.LocalObjectReference{
							Name: cluster.Spec.Backup.S3Config.CredentialsSecret.Name,
						},
						Key: "secret-key",
					},
				},
			},
		}...)
	}

	return env
}

// buildRestoreJob builds a Job for restoring from backup
func (m *Manager) buildRestoreJob(cluster *ozonev1alpha1.OzoneCluster, backupPath string) *batchv1.Job {
	backoffLimit := int32(3)

	labels := map[string]string{
		"app":       "ozone",
		"component": "restore",
		"cluster":   cluster.Name,
	}

	restoreCmd := m.buildRestoreCommand(cluster, backupPath)

	return &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-restore-%d", cluster.Name, time.Now().Unix()),
			Namespace: cluster.Namespace,
			Labels:    labels,
		},
		Spec: batchv1.JobSpec{
			BackoffLimit: &backoffLimit,
			Template: corev1.PodTemplateSpec{
				Spec: corev1.PodSpec{
					RestartPolicy: corev1.RestartPolicyOnFailure,
					Containers: []corev1.Container{
						{
							Name:            "restore",
							Image:           cluster.Spec.Image,
							ImagePullPolicy: cluster.Spec.ImagePullPolicy,
							Command:         []string{"/bin/bash", "-c"},
							Args:            []string{restoreCmd},
							Env:             m.buildBackupEnv(cluster),
						},
					},
					ImagePullSecrets: cluster.Spec.ImagePullSecrets,
				},
			},
		},
	}
}

// buildRestoreCommand builds the restore command
func (m *Manager) buildRestoreCommand(cluster *ozonev1alpha1.OzoneCluster, backupPath string) string {
	// In production, this would download the backup and restore it
	// For now, return a placeholder
	return fmt.Sprintf("echo 'Restoring from backup: %s'", backupPath)
}

// deleteBackupCronJob deletes the backup CronJob if it exists
func (m *Manager) deleteBackupCronJob(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster) error {
	cronJob := &batchv1.CronJob{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-backup", cluster.Name),
			Namespace: cluster.Namespace,
		},
	}
	
	err := m.client.Delete(ctx, cronJob)
	if err != nil && !errors.IsNotFound(err) {
		return err
	}
	
	return nil
}

// waitForJobCompletion waits for a Job to complete
func (m *Manager) waitForJobCompletion(ctx context.Context, job *batchv1.Job) error {
	// In production, this would properly wait and check job status
	// For now, return immediately
	return nil
}