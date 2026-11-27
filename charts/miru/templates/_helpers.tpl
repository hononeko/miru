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
Resolve the container image reference, supporting optional SHA-256 digest pinning.
*/}}
{{- define "miru.image" -}}
{{- if .Values.image.digest -}}
{{- if .Values.image.tag -}}
{{- printf "%s:%s@%s" .Values.image.repository .Values.image.tag .Values.image.digest -}}
{{- else -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- end -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository (.Values.image.tag | default .Chart.AppVersion) -}}
{{- end -}}
{{- end }}

{{/*
Name of the ConfigMap holding non-secret application settings (truncated before suffix to stay <= 63 chars).
*/}}
{{- define "miru.configMapName" -}}
{{- printf "%s-config" (include "miru.fullname" . | trunc 56 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the PersistentVolumeClaim created for SQLite data (truncated before suffix to stay <= 63 chars).
*/}}
{{- define "miru.pvcName" -}}
{{- printf "%s-data" (include "miru.fullname" . | trunc 58 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the headless Service used by StatefulSet pods (truncated before suffix to stay <= 63 chars).
*/}}
{{- define "miru.headlessServiceName" -}}
{{- printf "%s-headless" (include "miru.fullname" . | trunc 54 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the Grafana dashboard ConfigMap (truncated before suffix to stay <= 63 chars).
*/}}
{{- define "miru.grafanaDashboardConfigMapName" -}}
{{- printf "%s-grafana-dashboard" (include "miru.fullname" . | trunc 45 | trimSuffix "-") }}
{{- end }}

{{/*
Resolve the Secret name to mount via envFrom
*/}}
{{- define "miru.secretName" -}}
{{- if .Values.existingSecret }}
{{- .Values.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "miru.fullname" . | trunc 55 | trimSuffix "-") }}
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
{{- $sessionSecret := include "miru.sessionSecret" . | trim -}}
{{- if and (include "miru.authEnabled" .) (lt (len $sessionSecret) 32) -}}
{{- fail "auth.sessionSecret must be at least 32 characters unless auth.mode is none. Set it with --set auth.sessionSecret=\"$(openssl rand -base64 48)\", or use existingSecret." -}}
{{- end -}}
{{- $encryptionKey := include "miru.encryptionKey" . | trim -}}
{{- if and $encryptionKey (lt (len $encryptionKey) 32) -}}
{{- fail "security.encryptionKey must be at least 32 characters when set, for example $(openssl rand -base64 48)." -}}
{{- end -}}
{{- end -}}
{{- if and (eq (lower .Values.auth.mode) "local") (not .Values.auth.allowLocalLogin) (not .Values.auth.oidc.enabled) -}}
{{- fail "auth.allowLocalLogin=false with auth.mode=local requires auth.oidc.enabled=true (or set auth.mode=none to disable built-in authentication)." -}}
{{- end -}}
{{- $workloadKind := (.Values.workload).kind | default "Deployment" -}}
{{- if and (ne $workloadKind "Deployment") (ne $workloadKind "StatefulSet") -}}
{{- fail (printf "workload.kind must be Deployment or StatefulSet, got %q." $workloadKind) -}}
{{- end -}}
{{- $dataSource := .Values.persistence.dataSource | default dict -}}
{{- $dataSourceRef := .Values.persistence.dataSourceRef | default dict -}}
{{- if and (not (empty $dataSource)) (not (empty $dataSourceRef)) -}}
{{- fail "persistence.dataSource and persistence.dataSourceRef cannot both be set." -}}
{{- end -}}
{{- if and .Values.persistence.existingClaim (or (not (empty $dataSource)) (not (empty $dataSourceRef))) -}}
{{- fail "persistence.dataSource and persistence.dataSourceRef cannot be set when persistence.existingClaim is set." -}}
{{- end -}}
{{- if eq $workloadKind "Deployment" -}}
{{- $strategy := .Values.strategy | default dict -}}
{{- if eq (get $strategy "type") "RollingUpdate" -}}
{{- fail "strategy.type=RollingUpdate is not supported for Deployment (with either SQLite or PostgreSQL): Miru has no leader election, so overlapping pods during rollout would record duplicate streams, trigger duplicate guardrails, and emit duplicate webhooks. Use strategy.type=Recreate (or workload.kind=StatefulSet)." -}}
{{- end -}}
{{- if and (eq (get $strategy "type") "Recreate") (get $strategy "rollingUpdate") -}}
{{- fail "strategy.rollingUpdate cannot be set when strategy.type is Recreate." -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
Whether Miru built-in authentication is active (auth.mode != none)
*/}}
{{- define "miru.authEnabled" -}}
{{- if ne (lower .Values.auth.mode) "none" -}}
true
{{- end -}}
{{- end }}

{{/*
Whether OIDC single sign-on is active
*/}}
{{- define "miru.oidcEnabled" -}}
{{- if and (include "miru.authEnabled" .) (or .Values.auth.oidc.enabled (eq (lower .Values.auth.mode) "oidc")) -}}
true
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
Name of the ClusterIP Service that exposes the dedicated metrics port (truncated before suffix to avoid colliding with fullname).
*/}}
{{- define "miru.metricsServiceName" -}}
{{- printf "%s-metrics" (include "miru.fullname" . | trunc 55 | trimSuffix "-") }}
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

{{/*
Whether the workload controller is a StatefulSet
*/}}
{{- define "miru.isStatefulSet" -}}
{{- if eq (((.Values.workload).kind) | default "Deployment") "StatefulSet" -}}
true
{{- end -}}
{{- end }}

{{/*
Whether SQLite persistence is managed via StatefulSet volumeClaimTemplates
*/}}
{{- define "miru.useVolumeClaimTemplate" -}}
{{- if and (include "miru.isStatefulSet" .) (include "miru.persistenceEnabled" .) (not .Values.persistence.existingClaim) -}}
true
{{- end -}}
{{- end }}

{{/*
Shared PersistentVolumeClaim spec used by both standalone PVC and StatefulSet volumeClaimTemplates
*/}}
{{- define "miru.pvcSpec" -}}
accessModes:
  {{- range .Values.persistence.accessModes }}
  - {{ . | quote }}
  {{- end }}
resources:
  requests:
    storage: {{ .Values.persistence.size | quote }}
{{- if .Values.persistence.storageClassName }}
{{- if eq "-" .Values.persistence.storageClassName }}
storageClassName: ""
{{- else }}
storageClassName: {{ .Values.persistence.storageClassName | quote }}
{{- end }}
{{- end }}
{{- with .Values.persistence.dataSourceRef }}
dataSourceRef:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.persistence.dataSource }}
dataSource:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Shared PodTemplateSpec rendered inside Deployment and StatefulSet
*/}}
{{- define "miru.podTemplate" -}}
metadata:
  annotations:
    checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    {{- if not .Values.existingSecret }}
    checksum/secret: {{ include (print $.Template.BasePath "/secret.yaml") . | sha256sum }}
    {{- end }}
    {{- with .Values.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  labels:
    {{- include "miru.labels" . | nindent 4 }}
    {{- with .Values.podLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  {{- with .Values.imagePullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  serviceAccountName: {{ include "miru.serviceAccountName" . }}
  automountServiceAccountToken: {{ .Values.serviceAccount.automount }}
  enableServiceLinks: {{ .Values.enableServiceLinks }}
  {{- if not (kindIs "invalid" .Values.terminationGracePeriodSeconds) }}
  terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds }}
  {{- end }}
  {{- with .Values.priorityClassName }}
  priorityClassName: {{ . }}
  {{- end }}
  {{- with .Values.podSecurityContext }}
  securityContext:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  containers:
    - name: {{ .Chart.Name }}
      {{- with .Values.securityContext }}
      securityContext:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      image: {{ include "miru.image" . | quote }}
      imagePullPolicy: {{ .Values.image.pullPolicy }}
      envFrom:
        - configMapRef:
            name: {{ include "miru.configMapName" . }}
        - secretRef:
            name: {{ include "miru.secretName" . }}
        {{- with .Values.extraEnvFrom }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- with .Values.extraEnv }}
      env:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      ports:
        - name: http
          containerPort: {{ .Values.server.port }}
          protocol: TCP
        {{- if include "miru.dedicatedMetricsPort" . }}
        - name: metrics
          containerPort: {{ .Values.metrics.port }}
          protocol: TCP
        {{- end }}
      {{- if .Values.startupProbe.enabled }}
      startupProbe:
        {{- toYaml (omit .Values.startupProbe "enabled") | nindent 8 }}
      {{- end }}
      {{- if .Values.livenessProbe.enabled }}
      livenessProbe:
        {{- toYaml (omit .Values.livenessProbe "enabled") | nindent 8 }}
      {{- end }}
      {{- if .Values.readinessProbe.enabled }}
      readinessProbe:
        {{- toYaml (omit .Values.readinessProbe "enabled") | nindent 8 }}
      {{- end }}
      {{- with .Values.resources }}
      resources:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      volumeMounts:
        - name: data
          mountPath: /data
        - name: tmp
          mountPath: /tmp
        {{- with .Values.extraVolumeMounts }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
  volumes:
    {{- if not (include "miru.useVolumeClaimTemplate" .) }}
    - name: data
      {{- if include "miru.persistenceEnabled" . }}
      persistentVolumeClaim:
        claimName: {{ .Values.persistence.existingClaim | default (include "miru.pvcName" .) }}
      {{- else }}
      emptyDir: {}
      {{- end }}
    {{- end }}
    - name: tmp
      {{- if .Values.tmpVolume.sizeLimit }}
      emptyDir:
        sizeLimit: {{ .Values.tmpVolume.sizeLimit }}
      {{- else }}
      emptyDir: {}
      {{- end }}
    {{- with .Values.extraVolumes }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- with .Values.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.affinity }}
  affinity:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
