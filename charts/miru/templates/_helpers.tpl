{{/*
Expand the name of the chart.
*/}}
{{- define "miru.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "miru.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "miru.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "miru.labels" -}}
helm.sh/chart: {{ include "miru.chart" . }}
{{ include "miru.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "miru.selectorLabels" -}}
app.kubernetes.io/name: {{ include "miru.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "miru.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "miru.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Resolve the Secret name to mount via envFrom
*/}}
{{- define "miru.secretName" -}}
{{- if .Values.existingSecret }}
{{- .Values.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "miru.fullname" .) }}
{{- end }}
{{- end }}

{{/*
Whether SQLite persistence is active
*/}}
{{- define "miru.persistenceEnabled" -}}
{{- if and .Values.persistence.enabled (eq (lower .Values.database.engine) "sqlite") -}}
true
{{- end -}}
{{- end }}

{{/*
Whether a dedicated metrics listener port is exposed separately from the main HTTP port
*/}}
{{- define "miru.dedicatedMetricsPort" -}}
{{- if and .Values.metrics.enabled (ne (int .Values.metrics.port) (int .Values.server.port)) -}}
true
{{- end -}}
{{- end }}

{{/*
Service port name used by ServiceMonitor for scraping /metrics
*/}}
{{- define "miru.metricsPortName" -}}
{{- if include "miru.dedicatedMetricsPort" . -}}
metrics
{{- else -}}
http
{{- end -}}
{{- end }}
