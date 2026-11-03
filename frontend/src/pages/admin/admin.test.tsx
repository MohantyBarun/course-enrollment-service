import { screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { AdminMovie, CinemaView } from '../../api/types';
import { movie } from '../../test/fixtures';
import { jsonResponse, mockFetch, renderAt, signInAsAdmin } from '../../test/helpers';
import { AdminMoviesPage } from './MoviesPage';
import { AdminShowsPage } from './ShowsPage';

const base = { ...movie, synopsis: 'A test movie.' };
const movies: AdminMovie[] = [
  { ...base, id: 21, title: 'Showing Now', visibility: 'NOW_SHOWING', upcomingShows: 4, showingIn: ['Mumbai', 'Pune'] },
  { ...base, id: 22, title: 'Opens Friday', visibility: 'COMING_SOON', upcomingShows: 0, showingIn: [] },
  { ...base, id: 23, title: 'Gemini', visibility: 'HIDDEN', upcomingShows: 0, showingIn: [] },
];
const cinemas: CinemaView[] = [{
  id: 3, name: 'Starlight', address: 'KIIT Road', cityId: 8, cityName: 'Bhubaneswar',
  screens: [{ id: 5, name: 'Audi 1', seatCount: 40, categories: ['CLASSIC'] }],
}];

function adminBackend() {
  return mockFetch((url) => {
    if (url.pathname === '/admin/movies') return jsonResponse(movies);
    if (url.pathname === '/admin/cinemas') return jsonResponse(cinemas);
    if (url.pathname === '/admin/cinemas/3/shows') return jsonResponse([]);
    return jsonResponse({}, 404);
  });
}

describe('AdminMoviesPage', () => {
  it('tells the admin where customers can see each movie', async () => {
    signInAsAdmin();
    adminBackend();
    renderAt('/admin/movies', <Route path="/admin/movies" element={<AdminMoviesPage />} />);

    expect(await screen.findByText('Now showing · 2 cities')).toBeInTheDocument();
    expect(screen.getByText('Coming soon')).toBeInTheDocument();
    expect(screen.getByText('Hidden from customers')).toBeInTheDocument();

    // A hidden or coming-soon movie gets a shortcut to schedule it; one already showing doesn't
    const links = screen.getAllByRole('link', { name: 'Schedule a show' });
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/admin/shows?movie=22', '/admin/shows?movie=23']);
  });
});

describe('AdminShowsPage', () => {
  it('opens the schedule form with the movie picked on the Movies page', async () => {
    signInAsAdmin();
    adminBackend();
    renderAt('/admin/shows?movie=23', <Route path="/admin/shows" element={<AdminShowsPage />} />);

    const select = await screen.findByLabelText('Movie');
    expect(select).toHaveValue('23');
  });
});
