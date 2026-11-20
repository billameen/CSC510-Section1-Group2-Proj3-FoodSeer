import React, { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { getCurrentUser, isAuthenticated } from '../services/api';

/**
 * Where each role lands after login, and where RoleRoute sends someone who
 * opens a page their role can't use.
 */
export const homeFor = (role) => {
  switch (role) {
    case 'ROLE_ADMIN':
    case 'ROLE_STAFF':
      return '/order-management';
    case 'ROLE_DRIVER':
      return '/driver-dashboard';
    case 'ROLE_CUSTOMER':
      return '/preferences';
    default:
      return '/';
  }
};

/**
 * Renders the page only for the listed roles. Not signed in goes to the login
 * page; signed in with another role goes to that role's home page.
 *
 * This is a convenience so people don't land on pages that would only show
 * errors. The backend enforces the same rules on every request
 * (docs/authorization-matrix.md), so hiding a page is never the protection.
 *
 * Usage: <RoleRoute roles={['ROLE_STAFF', 'ROLE_ADMIN']}>...</RoleRoute>
 */
const RoleRoute = ({ roles, children }) => {
  const [role, setRole] = useState(undefined);

  useEffect(() => {
    if (!isAuthenticated()) {
      setRole(null);
      return;
    }
    let cancelled = false;
    getCurrentUser()
      .then((user) => { if (!cancelled) setRole(user.role); })
      .catch(() => { if (!cancelled) setRole(null); });
    return () => { cancelled = true; };
  }, []);

  if (role === undefined) return null; // still loading
  if (role === null) return <Navigate to="/" replace />;
  if (!roles.includes(role)) return <Navigate to={homeFor(role)} replace />;
  return children;
};

export default RoleRoute;
