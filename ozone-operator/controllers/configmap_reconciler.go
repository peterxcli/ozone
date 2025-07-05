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
	"strings"

	"github.com/go-logr/logr"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	ozonev1alpha1 "github.com/apache/ozone-operator/api/v1alpha1"
)

func (r *OzoneClusterReconciler) reconcileConfigMap(ctx context.Context, cluster *ozonev1alpha1.OzoneCluster, logger logr.Logger) error {
	logger.Info("Reconciling ConfigMap")

	cm := r.buildConfigMap(cluster)
	if err := controllerutil.SetControllerReference(cluster, cm, r.Scheme); err != nil {
		return err
	}

	found := &corev1.ConfigMap{}
	err := r.Get(ctx, types.NamespacedName{Name: cm.Name, Namespace: cm.Namespace}, found)
	if err != nil && errors.IsNotFound(err) {
		logger.Info("Creating ConfigMap", "Name", cm.Name)
		return r.Create(ctx, cm)
	} else if err != nil {
		return err
	}

	// Update if needed
	if !isConfigMapEqual(found, cm) {
		found.Data = cm.Data
		logger.Info("Updating ConfigMap", "Name", cm.Name)
		return r.Update(ctx, found)
	}

	return nil
}

func (r *OzoneClusterReconciler) buildConfigMap(cluster *ozonev1alpha1.OzoneCluster) *corev1.ConfigMap {
	ozoneSiteXML := r.generateOzoneSiteXML(cluster)
	coreSiteXML := r.generateCoreSiteXML(cluster)
	
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-config", cluster.Name),
			Namespace: cluster.Namespace,
			Labels: map[string]string{
				"app":     "ozone",
				"cluster": cluster.Name,
			},
		},
		Data: map[string]string{
			"ozone-site.xml": ozoneSiteXML,
			"core-site.xml":  coreSiteXML,
		},
	}
}

func (r *OzoneClusterReconciler) generateOzoneSiteXML(cluster *ozonev1alpha1.OzoneCluster) string {
	var properties []string

	// Base configurations
	properties = append(properties, []string{
		formatProperty("ozone.enabled", "true"),
		formatProperty("ozone.cluster.id", cluster.Name),
		formatProperty("ozone.metadata.dirs", "/data/metadata"),
		formatProperty("ozone.scm.client.address", fmt.Sprintf("%s-scm-0.%s-scm:9860", cluster.Name, cluster.Name)),
		formatProperty("ozone.om.address", fmt.Sprintf("%s-om-0.%s-om:9862", cluster.Name, cluster.Name)),
	}...)

	// SCM HA configuration
	if cluster.Spec.SCM.EnableHA && cluster.Spec.SCM.Replicas > 1 {
		properties = append(properties, formatProperty("ozone.scm.ha.enable", "true"))
		properties = append(properties, formatProperty("ozone.scm.service.ids", cluster.Name))
		properties = append(properties, formatProperty(fmt.Sprintf("ozone.scm.nodes.%s", cluster.Name), r.buildNodeList("scm", cluster.Spec.SCM.Replicas)))
		
		for i := int32(0); i < cluster.Spec.SCM.Replicas; i++ {
			properties = append(properties, formatProperty(
				fmt.Sprintf("ozone.scm.address.%s.scm%d", cluster.Name, i),
				fmt.Sprintf("%s-scm-%d.%s-scm:9860", cluster.Name, i, cluster.Name),
			))
			properties = append(properties, formatProperty(
				fmt.Sprintf("ozone.scm.http-address.%s.scm%d", cluster.Name, i),
				fmt.Sprintf("%s-scm-%d.%s-scm:9876", cluster.Name, i, cluster.Name),
			))
		}
	}

	// OM HA configuration
	if cluster.Spec.OM.EnableHA && cluster.Spec.OM.Replicas > 1 {
		properties = append(properties, formatProperty("ozone.om.ha.enable", "true"))
		properties = append(properties, formatProperty("ozone.om.service.ids", cluster.Name))
		properties = append(properties, formatProperty(fmt.Sprintf("ozone.om.nodes.%s", cluster.Name), r.buildNodeList("om", cluster.Spec.OM.Replicas)))
		
		for i := int32(0); i < cluster.Spec.OM.Replicas; i++ {
			properties = append(properties, formatProperty(
				fmt.Sprintf("ozone.om.address.%s.om%d", cluster.Name, i),
				fmt.Sprintf("%s-om-%d.%s-om:9862", cluster.Name, i, cluster.Name),
			))
			properties = append(properties, formatProperty(
				fmt.Sprintf("ozone.om.http-address.%s.om%d", cluster.Name, i),
				fmt.Sprintf("%s-om-%d.%s-om:9874", cluster.Name, i, cluster.Name),
			))
		}
	}

	// Security configurations
	if cluster.Spec.Security != nil && cluster.Spec.Security.Enabled {
		if cluster.Spec.Security.KerberosEnabled {
			properties = append(properties, []string{
				formatProperty("ozone.security.enabled", "true"),
				formatProperty("ozone.http.auth.kerberos.principal", "HTTP/_HOST@EXAMPLE.COM"),
				formatProperty("ozone.http.auth.kerberos.keytab", "/etc/security/keytabs/HTTP.keytab"),
			}...)
		}
		if cluster.Spec.Security.TLSEnabled {
			properties = append(properties, []string{
				formatProperty("ozone.rpc.tls.enabled", "true"),
				formatProperty("ozone.http.security.enabled", "true"),
				formatProperty("ozone.security.ssl.keystore.location", "/opt/hadoop/etc/security/certificates/keystore.jks"),
				formatProperty("ozone.security.ssl.truststore.location", "/opt/hadoop/etc/security/certificates/truststore.jks"),
			}...)
		}
	}

	// Recon configuration
	if cluster.Spec.Recon != nil && cluster.Spec.Recon.Enabled {
		properties = append(properties, formatProperty("ozone.recon.address", fmt.Sprintf("%s-recon-0.%s-recon:9891", cluster.Name, cluster.Name)))
		properties = append(properties, formatProperty("ozone.recon.http-address", fmt.Sprintf("%s-recon-0.%s-recon:9888", cluster.Name, cluster.Name)))
		properties = append(properties, formatProperty("ozone.recon.db.dir", "/data/recon"))
	}

	// Apply custom overrides
	for key, value := range cluster.Spec.ConfigOverrides {
		properties = append(properties, formatProperty(key, value))
	}

	return generateXMLConfiguration(properties)
}

func (r *OzoneClusterReconciler) generateCoreSiteXML(cluster *ozonev1alpha1.OzoneCluster) string {
	var properties []string

	properties = append(properties, []string{
		formatProperty("fs.defaultFS", fmt.Sprintf("o3fs://%s.%s/", "bucket1", "vol1")),
		formatProperty("fs.o3fs.impl", "org.apache.hadoop.fs.ozone.OzoneFileSystem"),
		formatProperty("fs.AbstractFileSystem.o3fs.impl", "org.apache.hadoop.fs.ozone.OzFs"),
	}...)

	return generateXMLConfiguration(properties)
}

func (r *OzoneClusterReconciler) buildNodeList(prefix string, count int32) string {
	var nodes []string
	for i := int32(0); i < count; i++ {
		nodes = append(nodes, fmt.Sprintf("%s%d", prefix, i))
	}
	return strings.Join(nodes, ",")
}

func formatProperty(name, value string) string {
	return fmt.Sprintf(`
  <property>
    <name>%s</name>
    <value>%s</value>
  </property>`, name, value)
}

func generateXMLConfiguration(properties []string) string {
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<configuration>%s
</configuration>`, strings.Join(properties, ""))
}

func isConfigMapEqual(a, b *corev1.ConfigMap) bool {
	if len(a.Data) != len(b.Data) {
		return false
	}
	for key, value := range a.Data {
		if b.Data[key] != value {
			return false
		}
	}
	return true
}