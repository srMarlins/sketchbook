import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { HomeShelf } from './HomeShelf';

describe('<HomeShelf />', () => {
  test('renders title, description and children', () => {
    render(
      <HomeShelf
        title="Currently working"
        description="recent edits and active sketches"
      >
        <div>card-1</div>
        <div>card-2</div>
      </HomeShelf>,
    );
    expect(screen.getByText('Currently working')).toBeInTheDocument();
    expect(screen.getByText('recent edits and active sketches')).toBeInTheDocument();
    expect(screen.getByText('card-1')).toBeInTheDocument();
    expect(screen.getByText('card-2')).toBeInTheDocument();
  });

  test('shows empty state with no children', () => {
    render(<HomeShelf title="Empty" description="" />);
    expect(screen.getByText(/nothing here yet/i)).toBeInTheDocument();
  });

  test('renders See all link when seeAllHref provided', () => {
    render(
      <HomeShelf title="x" description="" seeAllHref="/projects?foo=bar">
        <div>card</div>
      </HomeShelf>,
    );
    const link = screen.getByRole('link', { name: /see all/i });
    expect(link).toHaveAttribute('href', '/projects?foo=bar');
  });
});
