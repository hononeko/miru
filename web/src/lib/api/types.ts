import type { components } from './schema';

type Schemas = components['schemas'];

export type HealthResponse = Schemas['HealthResponse'];
export type ReadinessResponse = Schemas['ReadinessResponse'];
export type ProblemDetails = Schemas['ProblemDetails'];

export type MiruRole = Schemas['MiruRole'];
export type AuthSource = Schemas['AuthSource'];
export type AuthStatusResponse = Schemas['AuthStatusResponse'];
export type AuthProvidersResponse = Schemas['AuthProvidersResponse'];
export type PrincipalResponse = Schemas['PrincipalResponse'];
export type AccountResponse = Schemas['AccountResponse'];
export type AccountListResponse = Schemas['AccountListResponse'];
export type CreateAccountRequest = Schemas['CreateAccountRequest'];
export type ApiKeyResponse = Schemas['ApiKeyResponse'];
export type ApiKeyListResponse = Schemas['ApiKeyListResponse'];
export type CreateApiKeyRequest = Schemas['CreateApiKeyRequest'];
export type CreateApiKeyResponse = Schemas['CreateApiKeyResponse'];

export type ActiveStreamsResponse = Schemas['ActiveStreamsResponse'];
export type ActiveStreamDto = Schemas['ActiveStreamDto'];
export type SessionStartedEventDto = Schemas['SessionStartedEventDto'];
export type SessionUpdatedEventDto = Schemas['SessionUpdatedEventDto'];
export type SessionStoppedEventDto = Schemas['SessionStoppedEventDto'];

export type UsersListResponse = Schemas['UsersListResponse'];
export type UserSummaryDto = Schemas['UserSummaryDto'];
export type UserDetailResponse = Schemas['UserDetailResponse'];
export type UserRecentPlayDto = Schemas['UserRecentPlayDto'];

export type HistoryItemDto = Schemas['HistoryItemDto'];
export type HistoryListResponse = Schemas['HistoryListResponse'];
export type HistoryDetailResponse = Schemas['HistoryDetailResponse'];

export type DailyPlayStatDto = Schemas['DailyPlayStatDto'];
export type TopMediaStatDto = Schemas['TopMediaStatDto'];
export type DecisionBreakdownStatDto = Schemas['DecisionBreakdownStatDto'];
export type OverviewStatsResponse = Schemas['OverviewStatsResponse'];
export type UserStatsResponse = Schemas['UserStatsResponse'];

export type LibrarySectionResponse = Schemas['LibrarySectionResponse'];
export type LibraryListResponse = Schemas['LibraryListResponse'];
export type LibrarySnapshotResponse = Schemas['LibrarySnapshotResponse'];
export type LibraryStatsResponse = Schemas['LibraryStatsResponse'];
export type RecentMediaItemResponse = Schemas['RecentMediaItemResponse'];
export type RecentMediaListResponse = Schemas['RecentMediaListResponse'];
export type LibrarySyncMode = Schemas['LibrarySyncMode'];
export type LibrarySyncResponse = Schemas['LibrarySyncResponse'];
export type TautulliImportResponse = Schemas['TautulliImportResponse'];

export type StatsRange = '7d' | '30d' | '90d' | 'all';
