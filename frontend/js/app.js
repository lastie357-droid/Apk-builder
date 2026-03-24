// Configuration
const API_URL = 'http://localhost:5000/api';
const SOCKET_URL = 'http://localhost:5000';

let socket = null;
let authToken = localStorage.getItem('authToken');
let currentUser = null;

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    if (authToken) {
        verifyToken();
    } else {
        showPage('login');
    }

    setupEventListeners();
});

// Event Listeners
function setupEventListeners() {
    // Auth forms
    document.getElementById('loginForm').addEventListener('submit', handleLogin);
    document.getElementById('registerForm').addEventListener('submit', handleRegister);
    document.getElementById('showRegister').addEventListener('click', (e) => {
        e.preventDefault();
        showPage('register');
    });
    document.getElementById('showLogin').addEventListener('click', (e) => {
        e.preventDefault();
        showPage('login');
    });
    document.getElementById('logoutBtn').addEventListener('click', handleLogout);

    // Navigation
    document.querySelectorAll('.list-group-item').forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            const page = e.currentTarget.dataset.page;
            showDashboardPage(page);
        });
    });

    // Refresh devices
    document.getElementById('refreshDevices').addEventListener('click', loadDevices);

    // APK Builder
    document.getElementById('apkBuilderForm').addEventListener('submit', handleAPKBuild);
}

// Authentication
async function handleLogin(e) {
    e.preventDefault();
    const email = document.getElementById('loginEmail').value;
    const password = document.getElementById('loginPassword').value;

    try {
        const response = await fetch(`${API_URL}/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        const data = await response.json();
        
        if (data.success) {
            authToken = data.token;
            currentUser = data.user;
            localStorage.setItem('authToken', authToken);
            initDashboard();
        } else {
            showAlert('Login failed: ' + data.error, 'danger');
        }
    } catch (error) {
        showAlert('Login error: ' + error.message, 'danger');
    }
}

async function handleRegister(e) {
    e.preventDefault();
    const name = document.getElementById('registerName').value;
    const email = document.getElementById('registerEmail').value;
    const password = document.getElementById('registerPassword').value;

    try {
        const response = await fetch(`${API_URL}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, password })
        });

        const data = await response.json();
        
        if (data.success) {
            authToken = data.token;
            currentUser = data.user;
            localStorage.setItem('authToken', authToken);
            initDashboard();
        } else {
            showAlert('Registration failed: ' + data.error, 'danger');
        }
    } catch (error) {
        showAlert('Registration error: ' + error.message, 'danger');
    }
}

async function verifyToken() {
    try {
        const response = await fetch(`${API_URL}/auth/me`, {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        const data = await response.json();
        
        if (data.success) {
            currentUser = data.user;
            initDashboard();
        } else {
            localStorage.removeItem('authToken');
            showPage('login');
        }
    } catch (error) {
        localStorage.removeItem('authToken');
        showPage('login');
    }
}

function handleLogout() {
    localStorage.removeItem('authToken');
    authToken = null;
    currentUser = null;
    if (socket) socket.disconnect();
    showPage('login');
}

// Dashboard
function initDashboard() {
    showPage('dashboard');
    document.getElementById('userName').textContent = currentUser.name;
    connectSocket();
    loadDevices();
    loadPermissions();
}

function showPage(page) {
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('registerPage').style.display = 'none';
    document.getElementById('dashboard').style.display = 'none';

    if (page === 'login') {
        document.getElementById('loginPage').style.display = 'flex';
    } else if (page === 'register') {
        document.getElementById('registerPage').style.display = 'flex';
    } else if (page === 'dashboard') {
        document.getElementById('dashboard').style.display = 'block';
    }
}

function showDashboardPage(page) {
    document.querySelectorAll('.content-page').forEach(p => p.style.display = 'none');
    document.querySelectorAll('.list-group-item').forEach(item => {
        item.classList.remove('active');
    });

    document.querySelector(`[data-page="${page}"]`).classList.add('active');
    
    if (page === 'devices') {
        document.getElementById('devicesPage').style.display = 'block';
        loadDevices();
    } else if (page === 'apk-builder') {
        document.getElementById('apkBuilderPage').style.display = 'block';
    } else if (page === 'logs') {
        document.getElementById('logsPage').style.display = 'block';
    }
}

// Socket.io
function connectSocket() {
    socket = io(SOCKET_URL, {
        auth: { token: authToken }
    });

    socket.on('connect', () => {
        console.log('Socket connected');
    });

    socket.on('device:connected', (device) => {
        console.log('Device connected:', device);
        loadDevices();
    });

    socket.on('device:disconnected', (data) => {
        console.log('Device disconnected:', data.deviceId);
        loadDevices();
    });

    socket.on('device:data', (data) => {
        console.log('Device data:', data);
    });
}

// Devices
async function loadDevices() {
    try {
        const response = await fetch(`${API_URL}/devices`, {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        const data = await response.json();
        
        if (data.success) {
            displayDevices(data.devices);
        }
    } catch (error) {
        console.error('Error loading devices:', error);
    }
}

function displayDevices(devices) {
    const container = document.getElementById('devicesList');
    
    if (devices.length === 0) {
        container.innerHTML = `
            <div class="col-12">
                <div class="alert alert-info">
                    No devices connected. Install the app on your device to get started.
                </div>
            </div>
        `;
        return;
    }

    container.innerHTML = devices.map(device => `
        <div class="col-md-4">
            <div class="card device-card ${device.isOnline ? 'device-online' : 'device-offline'}">
                <div class="card-body position-relative">
                    <span class="badge ${device.isOnline ? 'bg-success' : 'bg-secondary'} status-badge">
                        ${device.isOnline ? 'Online' : 'Offline'}
                    </span>
                    <h5 class="card-title">${device.deviceName}</h5>
                    <div class="device-info">
                        <p class="mb-1"><i class="bi bi-phone"></i> ${device.model || 'Unknown'}</p>
                        <p class="mb-1"><i class="bi bi-android2"></i> Android ${device.androidVersion || 'N/A'}</p>
                        <p class="mb-1"><i class="bi bi-clock"></i> ${new Date(device.lastSeen).toLocaleString()}</p>
                    </div>
                    <div class="device-actions">
                        <button class="btn btn-sm btn-primary" onclick="viewDevice('${device.deviceId}')">
                            <i class="bi bi-eye"></i> View
                        </button>
                        <button class="btn btn-sm btn-danger" onclick="removeDevice('${device.deviceId}')">
                            <i class="bi bi-trash"></i> Remove
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `).join('');
}

async function viewDevice(deviceId) {
    // Implement device details view
    alert('Device details: ' + deviceId);
}

async function removeDevice(deviceId) {
    if (!confirm('Are you sure you want to remove this device?')) return;

    try {
        const response = await fetch(`${API_URL}/devices/${deviceId}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        const data = await response.json();
        
        if (data.success) {
            showAlert('Device removed successfully', 'success');
            loadDevices();
        } else {
            showAlert('Failed to remove device', 'danger');
        }
    } catch (error) {
        showAlert('Error: ' + error.message, 'danger');
    }
}

// APK Builder
async function loadPermissions() {
    try {
        const response = await fetch(`${API_URL}/apk/permissions`);
        const data = await response.json();
        
        if (data.success) {
            displayPermissions(data.permissions);
        }
    } catch (error) {
        console.error('Error loading permissions:', error);
    }
}

function displayPermissions(permissions) {
    const container = document.getElementById('permissionsList');
    container.innerHTML = permissions.map(perm => `
        <div class="permission-item">
            <div class="form-check">
                <input class="form-check-input" type="checkbox" 
                       id="perm_${perm.name}" 
                       value="${perm.name}"
                       ${perm.required ? 'checked disabled' : ''}>
                <label class="form-check-label" for="perm_${perm.name}">
                    ${perm.name} ${perm.required ? '(Required)' : ''}
                </label>
            </div>
            <div class="permission-description">${perm.description}</div>
        </div>
    `).join('');
}

async function handleAPKBuild(e) {
    e.preventDefault();

    const appName = document.getElementById('appName').value;
    const packageName = document.getElementById('packageName').value;
    const serverUrl = document.getElementById('serverUrl').value;
    
    const permissions = Array.from(document.querySelectorAll('#permissionsList input:checked'))
        .map(input => input.value);

    try {
        const response = await fetch(`${API_URL}/apk/build`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${authToken}`
            },
            body: JSON.stringify({ appName, packageName, serverUrl, permissions })
        });

        const data = await response.json();
        
        if (data.success) {
            showAlert('APK configuration created! Check console for build instructions.', 'success');
            console.log('Build Instructions:', data.instructions);
        } else {
            showAlert('Failed to create APK config', 'danger');
        }
    } catch (error) {
        showAlert('Error: ' + error.message, 'danger');
    }
}

// Utilities
function showAlert(message, type = 'info') {
    const alertDiv = document.createElement('div');
    alertDiv.className = `alert alert-${type} alert-dismissible fade show position-fixed top-0 start-50 translate-middle-x mt-3`;
    alertDiv.style.zIndex = '9999';
    alertDiv.innerHTML = `
        ${message}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;
    document.body.appendChild(alertDiv);
    
    setTimeout(() => alertDiv.remove(), 5000);
}
