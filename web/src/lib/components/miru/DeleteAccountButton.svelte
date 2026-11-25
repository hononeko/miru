<script lang="ts">
	import { Popover } from 'bits-ui';
	import { Trash2 } from '@lucide/svelte';
	import type { AccountResponse } from '$api/types';
	import Button from '$components/ui/Button.svelte';
	import Confirmation from '$components/ui/Confirmation.svelte';

	type Props = {
		account: AccountResponse;
		/** Deletes the account. Resolves to the reason when that is not possible. */
		ondelete: (account: AccountResponse) => Promise<string | null>;
	};

	let { account, ondelete }: Props = $props();

	const titleId = $derived(`delete-account-${account.id}`);
	const description = $derived(
		'Signs them out and deletes their API keys and preferences. Watch history stays.' +
			(account.authProvider === 'OIDC' ? ' They can sign in with SSO again, as a new account.' : '')
	);

	let open = $state(false);
	let deleting = $state(false);
	let error = $state<string | null>(null);

	function handleOpenChange(next: boolean) {
		if (deleting) return;
		open = next;
		error = null;
	}

	async function confirm() {
		// A second press can land before the button is disabled
		if (deleting) return;
		deleting = true;
		error = null;
		try {
			error = await ondelete(account);
		} finally {
			deleting = false;
		}
		if (error === null) open = false;
	}
</script>

<Popover.Root bind:open={() => open, handleOpenChange}>
	<Popover.Trigger>
		{#snippet child({ props })}
			<Button {...props} variant="destructive" size="sm">
				<Trash2 class="h-3.5 w-3.5" />
				Delete
			</Button>
		{/snippet}
	</Popover.Trigger>
	<Popover.Portal>
		<Popover.Content
			side="top"
			align="end"
			sideOffset={8}
			collisionPadding={12}
			aria-labelledby={titleId}
			aria-describedby={`${titleId}-description`}
			class="z-50 w-72 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none data-[state=open]:animate-popover-in"
		>
			<Popover.Arrow class="text-border-strong" width={10} height={5} />
			<Confirmation
				{titleId}
				title={`Delete account '${account.username}'?`}
				{description}
				confirmLabel="Delete"
				busyLabel="Deleting…"
				busy={deleting}
				{error}
				oncancel={() => handleOpenChange(false)}
				onconfirm={confirm}
			/>
		</Popover.Content>
	</Popover.Portal>
</Popover.Root>
