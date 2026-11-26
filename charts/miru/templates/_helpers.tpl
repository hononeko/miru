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
A key of the Secret this chart created in an earlier release, decoded. Empty when the Secret or the key
does not exist, and always empty under `helm template`, `--dry-run=client` and Argo CD, which cannot read
the cluster. Takes (dict "context" $ "key" "NAME").
*/}}
{{- define "miru.previousSecretValue" -}}
{{- $secret := lookup "v1" "Secret" .context.Release.Namespace (include "miru.secretName" .context) -}}
{{- $data := (get $secret "data") | default dict -}}
{{- if hasKey $data .key -}}
{{- get $data .key | b64dec -}}
{{- end -}}
{{- end }}

{{/*
AUTH_SESSION_SECRET: auth.sessionSecret, or the value kept from the previous release when it is empty, so
an upgrade that does not pass it again does not blank it.
*/}}
{{- define "miru.sessionSecret" -}}
{{- .Values.auth.sessionSecret | default (include "miru.previousSecretValue" (dict "context" . "key" "AUTH_SESSION_SECRET")) -}}
{{- end }}

{{/*
MIRU_ENCRYPTION_KEY: security.encryptionKey, or the value kept from the previous release when it is empty.
Losing it makes the credentials Miru saved in the database unreadable.
*/}}
{{- define "miru.encryptionKey" -}}
{{- .Values.security.encryptionKey | default (include "miru.previousSecretValue" (dict "context" . "key" "MIRU_ENCRYPTION_KEY")) -}}
{{- end }}

{{/*
Refuse to render settings that would deploy a pod that cannot start or that would damage its data.
*/}}
{{- define "miru.validate" -}}
{{- if .Values.existingSecret -}}
{{- $ignored := list -}}
{{- if .Values.auth.sessionSecret }}{{ $ignored = append $ignored "auth.sessionSecret" }}{{ end -}}
{{- if .Values.security.encryptionKey }}{{ $ignored = append $ignored "security.encryptionKey" }}{{ end -}}
{{- if .Values.auth.adminPassword }}{{ $ignored = append $ignored "auth.adminPassword" }}{{ end -}}
{{- if .Values.plex.token }}{{ $ignored = append $ignored "plex.token" }}{{ end -}}
{{- if .Values.jellyfin.apiKey }}{{ $ignored = append $ignored "jellyfin.apiKey" }}{{ end -}}
{{- if .Values.database.postgres.password }}{{ $ignored = append $ignored "database.postgres.password" }}{{ end -}}
{{- if .Values.auth.oidc.clientSecret }}{{ $ignored = append $ignored "auth.oidc.clientSecret" }}{{ end -}}
{{- if $ignored -}}
{{- fail (printf "existingSecret is set, so the chart creates no Secret and would ignore %s. Put these in the %q Secret instead." (join ", " $ignored) .Values.existingSecret) -}}
{{- end -}}
{{- else -}}
{{- if and (ne (lower .Values.auth.mode) "none") (lt (len (include "miru.sessionSecret" .)) 32) -}}
{{- fail "auth.sessionSecret must be at least 32 characters unless auth.mode is none. Set it with --set auth.sessionSecret=\"$(openssl rand -base64 48)\", or use existingSecret." -}}
{{- end -}}
{{- $encryptionKey := include "miru.encryptionKey" . -}}
{{- if and $encryptionKey (lt (len $encryptionKey) 32) -}}
{{- fail "security.encryptionKey must be at least 32 characters when set, for example $(openssl rand -base64 48)." -}}
{{- end -}}
{{- end -}}
{{- if and (include "miru.persistenceEnabled" .) (eq (get (.Values.strategy | default dict) "type") "RollingUpdate") -}}
{{- fail "strategy.type=RollingUpdate cannot be used with SQLite persistence: the new pod would wait for the volume the old pod holds, or both would write the same database file. Use strategy.type=Recreate." -}}
{{- end -}}
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
Name of the ClusterIP Service that exposes the dedicated metrics port
*/}}
{{- define "miru.metricsServiceName" -}}
{{- printf "%s-metrics" (include "miru.fullname" .) | trunc 63 | trimSuffix "-" }}
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
