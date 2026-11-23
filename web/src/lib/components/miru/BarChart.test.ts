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
});
