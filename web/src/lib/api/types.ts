import type { components } from './schema';

type Schemas = components['schemas'];

export type HealthResponse = Schemas['HealthResponse'];
export type ReadinessResponse = Schemas['ReadinessResponse'];
export type ProblemDetails = Schemas['ProblemDetails'];

export type MiruRole = Schemas['MiruRole'];
export type AuthSource = Schemas['AuthSource'];
export type AuthProvidersResponse = Schemas['AuthProvidersResponse'];
export type PrincipalResponse = Schemas['PrincipalResponse'];
export type ChangePasswordRequest = Schemas['ChangePasswordRequest'];
export type UserPreferencesResponse = Schemas['UserPreferencesResponse'];
export type UpdateUserPreferencesRequest = Schemas['UpdateUserPreferencesRequest'];
export type AccountResponse = Schemas['AccountResponse'];
export type AccountListResponse = Schemas['AccountListResponse'];
export type CreateAccountRequest = Schemas['CreateAccountRequest'];
export type UpdateAccountRequest = Schemas['UpdateAccountRequest'];
export type ApiKeyResponse = Schemas['ApiKeyResponse'];
export type ApiKeyListResponse = Schemas['ApiKeyListResponse'];
export type CreateApiKeyRequest = Schemas['CreateApiKeyRequest'];
export type CreateApiKeyResponse = Schemas['CreateApiKeyResponse'];

export type ActiveStreamsResponse = Schemas['ActiveStreamsResponse'];
export type ActiveStreamDto = Schemas['ActiveStreamDto'];
export type SessionStartedEventDto = Schemas['SessionStartedEventDto'];
export type SessionUpdatedEventDto = Schemas['SessionUpdatedEventDto'];
export type SessionStoppedEventDto = Schemas['SessionStoppedEventDto'];
export type TerminateStreamRequest = Schemas['TerminateStreamRequest'];
export type TerminateStreamResponse = Schemas['TerminateStreamResponse'];

export type GuardrailRuleType = Schemas['GuardrailRuleType'];
export type GuardrailMode = Schemas['GuardrailMode'];
export type GuardrailRuleDto = Schemas['GuardrailRuleDto'];
export type GuardrailRulesResponse = Schemas['GuardrailRulesResponse'];
export type UpdateGuardrailRuleRequest = Schemas['UpdateGuardrailRuleRequest'];
export type TerminationAuditSource = Schemas['TerminationAuditSource'];
export type TerminationAuditAction = Schemas['TerminationAuditAction'];
export type TerminationAuditEntryDto = Schemas['TerminationAuditEntryDto'];
export type TerminationAuditListResponse = Schemas['TerminationAuditListResponse'];

export type WebhookEventType = Schemas['WebhookEventType'];
export type WebhookDeliveryStatus = Schemas['WebhookDeliveryStatus'];
export type WebhookTargetDto = Schemas['WebhookTargetDto'];
export type WebhookTargetsResponse = Schemas['WebhookTargetsResponse'];
export type CreateWebhookTargetRequest = Schemas['CreateWebhookTargetRequest'];
export type UpdateWebhookTargetRequest = Schemas['UpdateWebhookTargetRequest'];
export type WebhookDeliveryDto = Schemas['WebhookDeliveryDto'];
export type WebhookDeliveriesResponse = Schemas['WebhookDeliveriesResponse'];

export type UsersListResponse = Schemas['UsersListResponse'];
export type UserSummaryDto = Schemas['UserSummaryDto'];
export type UserDetailResponse = Schemas['UserDetailResponse'];
export type UserRecentPlayDto = Schemas['UserRecentPlayDto'];

export type HistoryItemDto = Schemas['HistoryItemDto'];
export type HistoryListResponse = Schemas['HistoryListResponse'];
export type HistoryDetailResponse = Schemas['HistoryDetailResponse'];

export type DailyMediaPlayDto = Schemas['DailyMediaPlayDto'];
export type DailyUserPlayDto = Schemas['DailyUserPlayDto'];
export type DailyPlayStatDto = Schemas['DailyPlayStatDto'];
export type TopMediaStatDto = Schemas['TopMediaStatDto'];
export type RewatchedMediaStatDto = Schemas['RewatchedMediaStatDto'];
export type DecisionBreakdownDto = Schemas['DecisionBreakdownDto'];
export type HeatmapCellDto = Schemas['HeatmapCellDto'];
export type HeatmapStatsResponse = Schemas['HeatmapStatsResponse'];
export type ClientBreakdownItemDto = Schemas['ClientBreakdownItemDto'];
export type ClientStatsResponse = Schemas['ClientStatsResponse'];
export type WrappedMonthStatDto = Schemas['WrappedMonthStatDto'];
export type WrappedStatsResponse = Schemas['WrappedStatsResponse'];
export type OverviewStatsResponse = Schemas['OverviewStatsResponse'];
export type UserStatsResponse = Schemas['UserStatsResponse'];

export type LibrarySectionResponse = Schemas['LibrarySectionResponse'];
export type LibraryListResponse = Schemas['LibraryListResponse'];
export type LibrarySnapshotResponse = Schemas['LibrarySnapshotResponse'];
export type LibraryGrowthDayResponse = Schemas['LibraryGrowthDayResponse'];
export type LibraryStatsResponse = Schemas['LibraryStatsResponse'];
export type RecentMediaItemResponse = Schemas['RecentMediaItemResponse'];
export type RecentMediaListResponse = Schemas['RecentMediaListResponse'];
export type StaleMediaItemResponse = Schemas['StaleMediaItemResponse'];
export type StaleMediaListResponse = Schemas['StaleMediaListResponse'];
export type StaleMediaSort = StaleMediaListResponse['sort'];
export type HeatmapRange = HeatmapStatsResponse['range'];
export type LibrarySyncMode = Schemas['LibrarySyncMode'];
export type LibrarySyncResponse = Schemas['LibrarySyncResponse'];
export type TautulliImportResponse = Schemas['TautulliImportResponse'];

export type MediaServerSummaryDto = Schemas['MediaServerSummaryDto'];
export type MediaServersResponse = Schemas['MediaServersResponse'];
export type ServerDataSummaryDto = Schemas['ServerDataSummaryDto'];
export type AdminServerStatusDto = Schemas['AdminServerStatusDto'];
export type AdminServersResponse = Schemas['AdminServersResponse'];
export type DisconnectServerResponse = Schemas['DisconnectServerResponse'];
export type RemoveServerResponse = Schemas['RemoveServerResponse'];

export type PlexCredentialSource = Schemas['PlexCredentialSource'];
export type PlexOAuthPinStatus = Schemas['PlexOAuthPinStatus'];
export type PlexConnectionStatus = Schemas['PlexConnectionStatus'];
export type PlexOAuthStartResponse = Schemas['PlexOAuthStartResponse'];
export type PlexOAuthPollResponse = Schemas['PlexOAuthPollResponse'];
export type PlexConnectionOptionDto = Schemas['PlexConnectionOptionDto'];
export type PlexDiscoveredServerDto = Schemas['PlexDiscoveredServerDto'];
export type PlexDiscoveredServersResponse = Schemas['PlexDiscoveredServersResponse'];
export type PlexSelectServerRequest = Schemas['PlexSelectServerRequest'];
export type PlexSelectServerResponse = Schemas['PlexSelectServerResponse'];

export type JellyfinQuickConnectStatus = Schemas['JellyfinQuickConnectStatus'];
export type JellyfinConnectionStatus = Schemas['JellyfinConnectionStatus'];
export type JellyfinQuickConnectStartRequest = Schemas['JellyfinQuickConnectStartRequest'];
export type JellyfinQuickConnectStartResponse = Schemas['JellyfinQuickConnectStartResponse'];
export type JellyfinQuickConnectPollResponse = Schemas['JellyfinQuickConnectPollResponse'];

export type MediaTechnicalSpecsDto = Schemas['MediaTechnicalSpecsDto'];
export type SeriesOverviewDto = Schemas['SeriesOverviewDto'];
export type SeasonDetailsDto = Schemas['SeasonDetailsDto'];
export type MediaPlaySummaryDto = Schemas['MediaPlaySummaryDto'];
export type MediaDetailsResponse = Schemas['MediaDetailsResponse'];

export type StatsRange = UserPreferencesResponse['defaultStatsRange'];
export type ThemePreference = UserPreferencesResponse['theme'];
export type TimeFormatPreference = UserPreferencesResponse['timeFormat'];
export type DateFormatPreference = UserPreferencesResponse['dateFormat'];
export type AnalyticsMetricPreference = UserPreferencesResponse['analyticsMetric'];
