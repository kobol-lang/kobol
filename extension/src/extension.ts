import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import * as https from 'https';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';
import { KOBOL_VERSION } from './version';   // single-sourced from gradle.properties


const GITHUB_RELEASE_BASE =
    `https://github.com/kobol-lang/kobol/releases/download/v${KOBOL_VERSION}`;

let client: LanguageClient | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;

export async function activate(context: vscode.ExtensionContext) {
    // Status bar — shows error count for the active .kbl file
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
    statusBarItem.command = 'workbench.action.problems.focus';
    statusBarItem.text = '$(check) Kobol';
    statusBarItem.tooltip = 'Kobol — click to open Problems panel';
    statusBarItem.show();
    context.subscriptions.push(statusBarItem);

    // Update status bar whenever diagnostics change
    context.subscriptions.push(
        vscode.languages.onDidChangeDiagnostics(() => updateStatusBar()),
        vscode.window.onDidChangeActiveTextEditor(() => updateStatusBar()),
    );

    // Register all commands
    registerCommands(context);

    // Start the language server
    const nativeBin = await resolveNativeBinary(context);
    if (nativeBin) {
        startLspWithNative(context, nativeBin);
        return;
    }

    const jarPath = resolveKobolcJar(context);
    if (!jarPath) {
        vscode.window.showWarningMessage(
            'Kobol: kobolc.jar not found. Build the compiler with ' +
            '`./gradlew :compiler:jar`, or set kobol.kobolcJar in settings.',
        );
        return;
    }

    const config = vscode.workspace.getConfiguration('kobol');
    const java   = config.get<string>('javaExecutable') || 'java';

    const serverOptions: ServerOptions = {
        command: java,
        args: ['-jar', jarPath, '--lsp'],
        transport: TransportKind.stdio,
    };
    startLspClient(context, serverOptions);
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Status bar
// ─────────────────────────────────────────────────────────────────────────────

function updateStatusBar() {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kobol') {
        statusBarItem?.hide();
        return;
    }
    statusBarItem?.show();
    const diags = vscode.languages.getDiagnostics(editor.document.uri);
    const errors   = diags.filter(d => d.severity === vscode.DiagnosticSeverity.Error).length;
    const warnings = diags.filter(d => d.severity === vscode.DiagnosticSeverity.Warning).length;

    if (errors > 0) {
        statusBarItem!.text = `$(error) Kobol ${errors} error${errors > 1 ? 's' : ''}`;
        statusBarItem!.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
    } else if (warnings > 0) {
        statusBarItem!.text = `$(warning) Kobol ${warnings} warning${warnings > 1 ? 's' : ''}`;
        statusBarItem!.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    } else {
        statusBarItem!.text = '$(check) Kobol';
        statusBarItem!.backgroundColor = undefined;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Command registration
// ─────────────────────────────────────────────────────────────────────────────

function registerCommands(context: vscode.ExtensionContext) {
    context.subscriptions.push(
        vscode.commands.registerCommand('kobol.restartServer', async () => {
            await client?.stop();
            await client?.start();
            vscode.window.showInformationMessage('Kobol: language server restarted');
        }),

        vscode.commands.registerCommand('kobol.showVersion', () => {
            vscode.window.showInformationMessage(`Kobol Language Support v${KOBOL_VERSION}`);
        }),

        vscode.commands.registerCommand('kobol.build', () => runTask('build')),
        vscode.commands.registerCommand('kobol.run',   () => runTask('run')),
        vscode.commands.registerCommand('kobol.test',  () => runTask('test')),
        vscode.commands.registerCommand('kobol.check', () => runTask('check')),
        vscode.commands.registerCommand('kobol.clean', () => runTask('clean')),
    );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Build/Run/Test/Check/Clean tasks
// ─────────────────────────────────────────────────────────────────────────────

function runTask(taskName: string) {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showErrorMessage('Kobol: no workspace folder open');
        return;
    }

    const config = vscode.workspace.getConfiguration('kobol');
    const buildTool = config.get<string>('buildTool') || 'auto';
    const { cmd, args } = resolveCommand(folder.uri.fsPath, taskName, buildTool);

    const task = new vscode.Task(
        { type: 'kobol', task: taskName },
        folder,
        `Kobol: ${taskName}`,
        'kobol',
        new vscode.ShellExecution(cmd, args, { cwd: folder.uri.fsPath }),
        ['$kobolc'],
    );
    task.group = taskName === 'build' ? vscode.TaskGroup.Build : undefined;
    task.presentationOptions = {
        reveal: vscode.TaskRevealKind.Always,
        panel:  vscode.TaskPanelKind.Shared,
        clear:  true,
    };

    vscode.tasks.executeTask(task);
}

function resolveCommand(
    cwd: string,
    taskName: string,
    buildTool: string,
): { cmd: string; args: string[] } {
    const useGradle = buildTool === 'gradle' ||
        (buildTool === 'auto' && (
            fs.existsSync(path.join(cwd, 'build.gradle.kts')) ||
            fs.existsSync(path.join(cwd, 'build.gradle'))
        ));

    if (useGradle) {
        const wrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
        const gradleTask = {
            build: [':compiler:jar'],
            run:   ['run'],
            test:  ['test'],
            check: [':compiler:compileKotlin'],
            clean: ['clean'],
        }[taskName] ?? [taskName];
        return { cmd: wrapper, args: [...gradleTask, '--no-daemon'] };
    }

    // kobol CLI fallback
    return { cmd: 'kobol', args: [taskName] };
}

// ─────────────────────────────────────────────────────────────────────────────
//  LSP startup helpers
// ─────────────────────────────────────────────────────────────────────────────

function startLspWithNative(context: vscode.ExtensionContext, binaryPath: string) {
    const serverOptions: ServerOptions = {
        command: binaryPath,
        args: ['--lsp'],
        transport: TransportKind.stdio,
    };
    startLspClient(context, serverOptions);
}

function startLspClient(context: vscode.ExtensionContext, serverOptions: ServerOptions) {
    const config = vscode.workspace.getConfiguration('kobol');
    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'kobol' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.kbl'),
        },
        traceOutputChannel: config.get('trace.server') !== 'off'
            ? vscode.window.createOutputChannel('Kobol LSP Trace')
            : undefined,
    };

    client = new LanguageClient(
        'kobol',
        'Kobol Language Server',
        serverOptions,
        clientOptions,
    );
    client.start();
    context.subscriptions.push(client);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Native binary resolution + auto-download
// ─────────────────────────────────────────────────────────────────────────────

async function resolveNativeBinary(
    context: vscode.ExtensionContext,
): Promise<string | undefined> {
    const setting = vscode.workspace.getConfiguration('kobol').get<string>('nativeBinaryPath');
    if (setting && fs.existsSync(setting)) return setting;

    const exeSuffix = process.platform === 'win32' ? '.exe' : '';
    const binName   = `kobol${exeSuffix}`;

    const bundled = path.join(context.extensionPath, 'bin', binName);
    if (fs.existsSync(bundled)) return bundled;

    const cacheDir  = path.join(context.globalStorageUri.fsPath, 'bin');
    const cachedBin = path.join(cacheDir, binName);
    if (fs.existsSync(cachedBin)) return cachedBin;

    const assetName = platformAssetName();
    if (!assetName) return undefined;

    const downloaded = await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `Kobol: downloading native binary for ${process.platform}/${process.arch}…`,
            cancellable: false,
        },
        async () => {
            try {
                const archiveSuffix = process.platform === 'win32' ? '.zip' : '.tar.gz';
                const url = `${GITHUB_RELEASE_BASE}/${assetName}${archiveSuffix}`;
                const archivePath = path.join(cacheDir, `${assetName}${archiveSuffix}`);
                fs.mkdirSync(cacheDir, { recursive: true });
                await downloadFile(url, archivePath);
                await extractBinary(archivePath, cacheDir, binName);
                if (process.platform !== 'win32') fs.chmodSync(cachedBin, 0o755);
                return cachedBin;
            } catch (err) {
                vscode.window.showWarningMessage(
                    `Kobol: failed to download native binary: ${err}. Falling back to JVM mode.`,
                );
                return undefined;
            }
        },
    );
    return downloaded as string | undefined;
}

function platformAssetName(): string | undefined {
    const p = process.platform, a = process.arch;
    if (p === 'darwin' && a === 'arm64') return 'kobol-macos-arm64';
    if (p === 'darwin' && a === 'x64')   return 'kobol-macos-x86_64';
    if (p === 'linux'  && a === 'x64')   return 'kobol-linux-x86_64';
    if (p === 'linux'  && a === 'arm64') return 'kobol-linux-aarch64';
    if (p === 'win32'  && a === 'x64')   return 'kobol-windows-x86_64';
    return undefined;
}

function downloadFile(url: string, dest: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        const request = (targetUrl: string) =>
            https.get(targetUrl, (res) => {
                if (res.statusCode === 301 || res.statusCode === 302) {
                    request(res.headers.location!); return;
                }
                if (res.statusCode !== 200) {
                    reject(new Error(`HTTP ${res.statusCode} for ${targetUrl}`)); return;
                }
                res.pipe(file);
                file.on('finish', () => file.close(() => resolve()));
            });
        request(url);
        file.on('error', (err) => { fs.unlink(dest, () => reject(err)); });
    });
}

async function extractBinary(archivePath: string, destDir: string, binName: string): Promise<void> {
    const { execFile } = await import('child_process');
    const { promisify } = await import('util');
    const execAsync = promisify(execFile);

    if (archivePath.endsWith('.tar.gz')) {
        await execAsync('tar', ['-xzf', archivePath, '-C', destDir, binName]);
    } else if (archivePath.endsWith('.zip')) {
        await execAsync('powershell', [
            '-NoProfile', '-Command',
            `Expand-Archive -Force -Path "${archivePath}" -DestinationPath "${destDir}"`,
        ]);
    } else {
        throw new Error(`Unknown archive format: ${archivePath}`);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Fat-jar fallback
// ─────────────────────────────────────────────────────────────────────────────

function resolveKobolcJar(context: vscode.ExtensionContext): string | undefined {
    const setting = vscode.workspace.getConfiguration('kobol').get<string>('kobolcJar');
    if (setting && fs.existsSync(setting)) return setting;

    const bundled = path.join(context.extensionPath, 'kobolc.jar');
    if (fs.existsSync(bundled)) return bundled;

    for (const folder of vscode.workspace.workspaceFolders ?? []) {
        const candidate = path.join(folder.uri.fsPath, 'compiler', 'build', 'libs', 'kobolc.jar');
        if (fs.existsSync(candidate)) return candidate;
    }
    return undefined;
}
