/** How long a stream may stay active after its termination was accepted before it counts as still playing. */
export const TERMINATION_OVERDUE_MS = 60_000;

export interface PendingTermination {
	key: string;
	label: string;
	reason: string;
	overdue: boolean;
	dismissed: boolean;
}

/**
 * Follows streams from the accepted termination until they stop. Plex only tells the viewer's app to stop when the app
 * next reports its progress, so an accepted termination is not yet a stopped stream.
 */
export class TerminationTracker {
	items = $state<PendingTermination[]>([]);
	private timers = new Map<string, ReturnType<typeof setTimeout>>();

	get notices(): PendingTermination[] {
		return this.items.filter((item) => !item.dismissed);
	}

	isTerminating(key: string): boolean {
		return this.items.some((item) => item.key === key && !item.overdue);
	}

	start(key: string, label: string, reason: string): void {
		this.remove(key);
		this.items = [...this.items, { key, label, reason, overdue: false, dismissed: false }];
		this.timers.set(
			key,
			setTimeout(() => this.update(key, { overdue: true, dismissed: false }), TERMINATION_OVERDUE_MS)
		);
	}

	/** Forgets the streams that are no longer active. */
	sync(activeKeys: Set<string>): void {
		for (const { key } of this.items.filter((item) => !activeKeys.has(item.key))) {
			this.remove(key);
		}
	}

	dismiss(key: string): void {
		this.update(key, { dismissed: true });
	}

	dispose(): void {
		for (const { key } of [...this.items]) {
			this.remove(key);
		}
	}

	private update(key: string, changes: Partial<PendingTermination>): void {
		this.items = this.items.map((item) => (item.key === key ? { ...item, ...changes } : item));
	}

	private remove(key: string): void {
		clearTimeout(this.timers.get(key));
		this.timers.delete(key);
		if (this.items.some((item) => item.key === key)) {
			this.items = this.items.filter((item) => item.key !== key);
		}
	}
}
