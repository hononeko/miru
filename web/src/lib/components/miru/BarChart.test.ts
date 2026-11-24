import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { afterEach, describe, expect, it } from 'vitest';
import BarChart, { type BarPoint } from './BarChart.svelte';

describe('BarChart', () => {
	afterEach(() => {
		cleanup();
	});

	it('opens a click popover without native title hover tooltips and switches cleanly between bars', async () => {
		const points: BarPoint[] = [
			{
				id: '2026-11-17',
				label: '17',
				subLabel: 'Nov',
				value: 4,
				tooltip: '2026-11-17: 4 plays (2h 0m)',
				ariaLabel: 'Nov 17: 4 plays'
			},
			{
				id: '2026-11-18',
				label: '18',
				value: 10,
				tooltip: '2026-11-18: 10 plays (5h 0m)',
				ariaLabel: 'Nov 18: 10 plays'
			}
		];

		const { container } = render(BarChart, { props: { points } });

		expect(container.querySelector('[title]')).toBeNull();

		const bar1 = screen.getByRole('button', { name: 'Nov 17: 4 plays' });
		const bar2 = screen.getByRole('button', { name: 'Nov 18: 10 plays' });

		expect(screen.queryByText('2026-11-17: 4 plays (2h 0m)')).toBeNull();

		await fireEvent.click(bar1);
		expect(screen.getByText('2026-11-17: 4 plays (2h 0m)')).toBeTruthy();

		await fireEvent.click(bar2);
		expect(bar1.getAttribute('aria-expanded')).toBe('false');
		expect(bar2.getAttribute('aria-expanded')).toBe('true');
		expect(screen.getByText('2026-11-18: 10 plays (5h 0m)')).toBeTruthy();
		await waitFor(() => {
			expect(screen.queryByText('2026-11-17: 4 plays (2h 0m)')).toBeNull();
		});
	});

	it('renders stacked user segments and custom detail snippet with close callback', async () => {
		const points: BarPoint[] = [
			{
				id: '2026-11-18',
				label: '18',
				subLabel: 'Nov',
				value: 5,
				ariaLabel: 'Nov 18: 5 plays',
				segments: [
					{ label: 'Alice', value: 3, colorClass: 'bg-cyan' },
					{ label: 'Bob', value: 2, colorClass: 'bg-primary' }
				]
			}
		];

		const detail = createRawSnippet<[BarPoint, number, () => void]>((getPoint, _getIdx, getClose) => ({
			render: () => `
				<div data-testid="custom-detail">
					<span>${getPoint().id}: ${getPoint().value} total</span>
					<button type="button" data-close>Dismiss</button>
				</div>
			`,
			setup: (node) => {
				const btn = node.querySelector('[data-close]');
				const onClick = () => getClose()();
				btn?.addEventListener('click', onClick);
				return () => btn?.removeEventListener('click', onClick);
			}
		}));

		const { container } = render(BarChart, { props: { points, detail } });

		expect(container.querySelector('.bg-cyan')).toBeTruthy();
		expect(container.querySelector('.bg-primary')).toBeTruthy();

		await fireEvent.click(screen.getByRole('button', { name: 'Nov 18: 5 plays' }));
		expect(screen.getByText('2026-11-18: 5 total')).toBeTruthy();

		await fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
		await waitFor(() => {
			expect(screen.queryByText('2026-11-18: 5 total')).toBeNull();
		});
	});

	it('draws negative values below a shared baseline on the same scale and keeps positive-only charts full height', () => {
		const { container, unmount } = render(BarChart, {
			props: {
				points: [
					{
						id: 'a',
						label: '20',
						value: 30,
						segments: [{ label: 'Added', value: 30, colorClass: 'bg-chart-1' }],
						negative: { label: 'Removed', value: 10, colorClass: 'bg-chart-2' }
					},
					{
						id: 'b',
						label: '21',
						value: 0,
						segments: [{ label: 'Added', value: 0, colorClass: 'bg-chart-1' }],
						negative: { label: 'Removed', value: 20, colorClass: 'bg-chart-2' }
					}
				]
			}
		});

		const added = container.querySelectorAll<HTMLElement>('.rounded-t-sm');
		const removed = container.querySelectorAll<HTMLElement>('.rounded-b-sm');
		// The day without additions has no bar above the baseline
		expect(added).toHaveLength(1);
		expect(added[0].style.bottom).toBe('40%');
		expect(added[0].style.height).toBe('60%');
		expect(Array.from(removed, (bar) => [bar.style.top, bar.style.height])).toEqual([
			['60%', '20%'],
			['60%', '40%']
		]);
		unmount();

		const plain = render(BarChart, { props: { points: [{ id: 'a', label: '20', value: 5 }] } });
		const bar = plain.container.querySelector<HTMLElement>('.rounded-t-sm')!;
		expect([bar.style.bottom, bar.style.height]).toEqual(['0%', '100%']);
		expect(plain.container.querySelector('.rounded-b-sm')).toBeNull();
	});
});
