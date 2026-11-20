import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getAllUsers, updateUserRole, deleteUser, getCurrentUser, createUser } from '../services/api';

const UserManagement = () => {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editingUser, setEditingUser] = useState(null);
  const [newRole, setNewRole] = useState('');
  const [currentUserId, setCurrentUserId] = useState(null);
  const emptyAccount = { username: '', email: '', password: '', role: 'staff' };
  const [newAccount, setNewAccount] = useState(emptyAccount);
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();

  const fetchUsers = async () => {
    try {
      const user = await getCurrentUser();
      setCurrentUserId(user.id);
      
      // Check if user is admin
      if (user.role !== 'ROLE_ADMIN') {
        alert('Access denied. Admin privileges required.');
        navigate('/recommendations');
        return;
      }

      const usersData = await getAllUsers();
      setUsers(usersData);
    } catch (error) {
      console.error('Error fetching users:', error);
      navigate('/');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchUsers();
  }, []);

  const handleEditRole = (user) => {
    setEditingUser(user);
    setNewRole(user.role);
  };

  const handleUpdateRole = async () => {
    if (!newRole) {
      alert('Please select a role');
      return;
    }

    if (editingUser.id === currentUserId && newRole !== 'ROLE_ADMIN') {
      alert('Warning: You are changing your own admin role. You will lose admin access!');
    }

    try {
      await updateUserRole(editingUser.id, newRole);
      alert('User role updated successfully!');
      setEditingUser(null);
      setNewRole('');
      await fetchUsers();
    } catch (error) {
      console.error('Error updating user role:', error);
      alert(`Could not update the role: ${error.message}`);
    }
  };

  const handleDeleteUser = async (userId, username) => {
    if (userId === currentUserId) {
      alert('You cannot delete your own account!');
      return;
    }

    if (!window.confirm(`Are you sure you want to delete user ${username}?`)) {
      return;
    }

    try {
      await deleteUser(userId);
      alert('User deleted successfully!');
      await fetchUsers();
    } catch (error) {
      console.error('Error deleting user:', error);
      alert(`Could not delete the user: ${error.message}`);
    }
  };

  const handleCreateAccount = async (e) => {
    e.preventDefault();
    setCreating(true);
    try {
      await createUser(newAccount.username, newAccount.email, newAccount.password, newAccount.role);
      alert(`Created ${newAccount.role} account ${newAccount.username}.`);
      setNewAccount(emptyAccount);
      await fetchUsers();
    } catch (error) {
      alert(`Could not create the account: ${error.message}`);
    } finally {
      setCreating(false);
    }
  };

  const getRoleBadgeClass = (role) => {
    switch (role) {
      case 'ROLE_ADMIN':
        return 'role-badge admin';
      case 'ROLE_STAFF':
        return 'role-badge staff';
      default:
        return 'role-badge customer';
    }
  };

  const getRoleDisplayName = (role) => {
    switch (role) {
      case 'ROLE_ADMIN':
        return 'Admin';
      case 'ROLE_STAFF':
        return 'Staff';
      case 'ROLE_CUSTOMER':
        return 'Customer';
      case 'ROLE_DRIVER':
        return 'Driver';
      default:
        return role;
    }
  };

  const handleBack = () => {
    navigate('/recommendations');
  };

  if (loading) {
    return (
      <div className="user-management-container">
        <div className="loading">Loading users...</div>
      </div>
    );
  }

  return (
    <div className="user-management-container">
      <div className="dashboard-header">
        <h1>👥 User Management</h1>
        <button className="back-button" onClick={handleBack}>
          Back
        </button>
      </div>

      <div className="dashboard-stats">
        <div className="stat-card">
          <h3>Total Users</h3>
          <p className="stat-number">{users.length}</p>
        </div>
        <div className="stat-card">
          <h3>Admins</h3>
          <p className="stat-number">{users.filter(u => u.role === 'ROLE_ADMIN').length}</p>
        </div>
        <div className="stat-card">
          <h3>Staff</h3>
          <p className="stat-number">{users.filter(u => u.role === 'ROLE_STAFF').length}</p>
        </div>
        <div className="stat-card">
          <h3>Customers</h3>
          <p className="stat-number">{users.filter(u => u.role === 'ROLE_CUSTOMER').length}</p>
        </div>
      </div>

      {editingUser && (
        <div className="edit-role-modal">
          <div className="modal-content">
            <h2>Edit User Role</h2>
            <p><strong>User:</strong> {editingUser.username}</p>
            <p><strong>Email:</strong> {editingUser.email}</p>
            <div className="form-group">
              <label htmlFor="role">Select Role:</label>
              <select
                id="role"
                value={newRole}
                onChange={(e) => setNewRole(e.target.value)}
                className="role-select"
              >
                <option value="ROLE_CUSTOMER">Customer</option>
                <option value="ROLE_DRIVER">Driver</option>
                <option value="ROLE_STAFF">Staff</option>
                <option value="ROLE_ADMIN">Admin</option>
              </select>
            </div>
            <div className="modal-actions">
              <button
                className="cancel-button"
                onClick={() => {
                  setEditingUser(null);
                  setNewRole('');
                }}
              >
                Cancel
              </button>
              <button className="submit-button" onClick={handleUpdateRole}>
                Update Role
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="users-table-container">
        <h2>Create an account</h2>
        <p>Staff and admin accounts can only be created here; the public sign-up form is for customers and drivers.</p>
        <form className="create-account-form" onSubmit={handleCreateAccount}>
          <input
            className="form-input"
            placeholder="Username"
            value={newAccount.username}
            onChange={(e) => setNewAccount({ ...newAccount, username: e.target.value })}
            required
          />
          <input
            className="form-input"
            type="email"
            placeholder="Email"
            value={newAccount.email}
            onChange={(e) => setNewAccount({ ...newAccount, email: e.target.value })}
            required
          />
          <input
            className="form-input"
            type="password"
            placeholder="Password (8+ characters)"
            value={newAccount.password}
            onChange={(e) => setNewAccount({ ...newAccount, password: e.target.value })}
            required
          />
          <select
            className="role-select"
            aria-label="Role"
            value={newAccount.role}
            onChange={(e) => setNewAccount({ ...newAccount, role: e.target.value })}
          >
            <option value="staff">Staff</option>
            <option value="admin">Admin</option>
            <option value="driver">Driver</option>
            <option value="customer">Customer</option>
          </select>
          <button type="submit" className="submit-button" disabled={creating}>
            {creating ? 'Creating...' : 'Create account'}
          </button>
        </form>
      </div>

      <div className="users-table-container">
        <h2>All Users</h2>
        {users.length === 0 ? (
          <p>No users found.</p>
        ) : (
          <table className="users-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Username</th>
                <th>Email</th>
                <th>Role</th>
                <th>Cost Preference</th>
                <th>Dietary Restrictions</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map(user => (
                <tr key={user.id} className={user.id === currentUserId ? 'current-user' : ''}>
                  <td>{user.id}</td>
                  <td>
                    {user.username}
                    {user.id === currentUserId && <span className="you-badge"> (You)</span>}
                  </td>
                  <td>{user.email}</td>
                  <td>
                    <span className={getRoleBadgeClass(user.role)}>
                      {getRoleDisplayName(user.role)}
                    </span>
                  </td>
                  <td>{user.costPreference || '-'}</td>
                  <td>{user.dietaryRestrictions || '-'}</td>
                  <td className="actions-cell">
                    <button
                      className="edit-button"
                      onClick={() => handleEditRole(user)}
                    >
                      Change Role
                    </button>
                    <button
                      className="delete-button"
                      onClick={() => handleDeleteUser(user.id, user.username)}
                      disabled={user.id === currentUserId}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default UserManagement;

