'use strict';

const path = require('path');
const {
  app, BrowserWindow, shell, Menu, dialog, session,
} = require('electron');

const { INSTANCES, DEFAULT_INSTANCE, partitionFor } = require('./instances');
const store = require('./store');

app.setName('Gridline');

// Penpot is a canvas/WASM app; let it use the GPU properly.
app.commandLine.appendSwitch('enable-features', 'CanvasOopRasterization');

let mainWindow = null;

function resolveInstance() {
  if (process.env.GRIDLINE_URL) {
    return { key: 'custom', label: 'Custom', url: process.env.GRIDLINE_URL };
  }
  const key = process.env.GRIDLINE_INSTANCE
    || store.read().instance
    || DEFAULT_INSTANCE;
  const found = INSTANCES[key];
  if (!found) {
    return { key: DEFAULT_INSTANCE, ...INSTANCES[DEFAULT_INSTANCE] };
  }
  return { key, ...found };
}

function createWindow(instance) {
  const { bounds } = store.read();
  const partition = partitionFor(instance.url);

  const win = new BrowserWindow({
    width: bounds?.width ?? 1600,
    height: bounds?.height ?? 1000,
    x: bounds?.x,
    y: bounds?.y,
    minWidth: 1024,
    minHeight: 700,
    backgroundColor: '#18181a',
    title: 'Gridline',
    show: false,
    webPreferences: {
      partition,
      // We load a remote origin. Node must stay out of the renderer.
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      spellcheck: false,
      // Penpot renders through WebGL/WASM; software fallback is unusable.
      webgl: true,
    },
  });

  win.once('ready-to-show', () => win.show());

  const persistBounds = () => {
    if (!win.isDestroyed() && !win.isMinimized() && !win.isFullScreen()) {
      store.write({ bounds: win.getNormalBounds() });
    }
  };
  win.on('resize', persistBounds);
  win.on('move', persistBounds);

  const appOrigin = new URL(instance.url).origin;

  // Anything that is not our instance belongs in the real browser: OAuth
  // callbacks, docs links, shared prototype URLs on other hosts.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (new URL(url).origin === appOrigin) return { action: 'allow' };
    shell.openExternal(url);
    return { action: 'deny' };
  });

  win.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== appOrigin) {
      event.preventDefault();
      shell.openExternal(url);
    }
  });

  win.webContents.on('did-fail-load', (_e, code, description, failedUrl) => {
    // -3 is ERR_ABORTED, which fires on ordinary in-app navigations.
    if (code === -3) return;
    dialog.showMessageBox(win, {
      type: 'error',
      title: 'Cannot reach Gridline',
      message: `${instance.label} did not respond.`,
      detail: `${failedUrl}\n\n${description} (${code})\n\n`
        + 'For the home lab, check that Tailscale is connected on this Mac.',
      buttons: ['Retry', 'Close'],
      defaultId: 0,
    }).then(({ response }) => {
      if (response === 0) win.loadURL(instance.url);
    });
  });

  win.loadURL(instance.url);
  return win;
}

function switchInstance(key) {
  store.write({ instance: key });
  const next = resolveInstance();
  const old = mainWindow;
  mainWindow = createWindow(next);
  buildMenu(next);
  if (old && !old.isDestroyed()) old.destroy();
}

function buildMenu(instance) {
  const isMac = process.platform === 'darwin';

  const instanceItems = Object.entries(INSTANCES).map(([key, def]) => ({
    label: `${def.label} — ${def.url}`,
    type: 'radio',
    checked: instance.key === key,
    click: () => switchInstance(key),
  }));

  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    { role: 'fileMenu' },
    { role: 'editMenu' },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
        { role: 'toggleDevTools' },
      ],
    },
    {
      label: 'Instance',
      submenu: [
        ...instanceItems,
        { type: 'separator' },
        {
          label: 'Sign out of this instance',
          click: async () => {
            const part = session.fromPartition(partitionFor(instance.url));
            await part.clearStorageData({ storages: ['cookies', 'localstorage'] });
            if (mainWindow) mainWindow.loadURL(instance.url);
          },
        },
      ],
    },
    { role: 'windowMenu' },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// A second launch should focus the existing window, not open a rival one that
// fights over the same session partition.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    const instance = resolveInstance();
    mainWindow = createWindow(instance);
    buildMenu(instance);

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        mainWindow = createWindow(resolveInstance());
      }
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
