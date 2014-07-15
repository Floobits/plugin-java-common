package floobits.common.interfaces;

import floobits.common.*;
import floobits.common.protocol.handlers.BaseHandler;
import floobits.common.protocol.handlers.CreateAccountHandler;
import floobits.common.protocol.handlers.FlooHandler;
import floobits.common.protocol.handlers.LinkEditorHandler;
import floobits.common.protocol.FlooUser;

import floobits.dialogs.DialogBuilder;
import floobits.utilities.Flog;

import io.fletty.bootstrap.Bootstrap;
import io.fletty.channel.nio.NioEventLoopGroup;
import io.fletty.util.concurrent.ScheduledFuture;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public abstract class IContext {
    public final EditorScheduler editor;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    public String colabDir;
    public volatile BaseHandler handler;
    public Date lastChatMessage;
    public IFactory iFactory;
    protected Ignore ignoreTree;
    protected volatile NioEventLoopGroup loopGroup;

    public IContext() {
        editor = new EditorScheduler(this);
    }

    public boolean addGroup(Bootstrap b) {
        boolean b1 = false;
        try {
            lock.readLock().lock();
            if (loopGroup != null) {
                b.group(loopGroup);
                b1 = true;
            }
        } finally {
            lock.readLock().unlock();
        }
        return b1;
    }

    public ScheduledFuture setTimeout(int time, final Runnable runnable) {
        ScheduledFuture schedule = null;
        try {
            lock.readLock().lock();
            if (loopGroup != null) {
                schedule = loopGroup.schedule(runnable, time, TimeUnit.MILLISECONDS);
            }
        } catch(Throwable e) {
            Flog.warn(e);
        } finally {
            lock.readLock().unlock();
        }
        return schedule;
    }

    private boolean changePerms(FlooUrl flooUrl, String[] newPerms) {
        HTTPWorkspaceRequest workspace = API.getWorkspace(flooUrl, this);
        if (workspace == null) {
            return false;
        }

        String[] anonPerms = workspace.perms.get("AnonymousUser");
        if (anonPerms == null) {
            anonPerms = new String[]{};
        }
        Arrays.sort(anonPerms);
        Arrays.sort(newPerms);
        if (!Arrays.equals(anonPerms, newPerms)) {
            workspace.perms.put("AnonymousUser", newPerms);
            return API.updateWorkspace(flooUrl, workspace, this);
        }
        return true;
    }

    public void shareProject(final boolean _private_, String projectPath) {


        FlooUrl flooUrl = DotFloo.read(projectPath);

        String[] newPerms = _private_ ? new String[]{} : new String[]{"view_room"};

        if (flooUrl != null && changePerms(flooUrl, newPerms)) {
            joinWorkspace(flooUrl, projectPath, true);
            return;
        }

        PersistentJson persistentJson = PersistentJson.getInstance();
        for (Map.Entry<String, Map<String, Workspace>> i : persistentJson.workspaces.entrySet()) {
            Map<String, Workspace> workspaces = i.getValue();
            for (Map.Entry<String, Workspace> j : workspaces.entrySet()) {
                Workspace w = j.getValue();
                if (Utils.isSamePath(w.path, projectPath)) {
                    try {
                        flooUrl = new FlooUrl(w.url);
                    } catch (MalformedURLException e) {
                        Flog.warn(e);
                        continue;
                    }
                    if (changePerms(flooUrl, newPerms)) {
                        joinWorkspace(flooUrl, w.path, true);
                        return;
                    }
                }
            }
        }

        String host;
        FloorcJson floorcJson;
        try {
            floorcJson = Settings.get();
        } catch (Throwable e) {
            Flog.log("Invalid .floorc.json");
            statusMessage("Invalid .floorc.json");
            return;
        }
        int size = floorcJson.auth.size();
        if (size <= 0) {
            Flog.log("No credentials.");
            return;
        }
        String[] keys = new String[size];
        floorcJson.auth.keySet().toArray(keys);

        if (keys.length == 1) {
            host = keys[0];
        } else {
            host = selectAccount(keys);
        }

        if (host == null) {
            return;
        }

        String owner = floorcJson.auth.get(host).get("username");
        final String name = new File(projectPath).getName();
        List<String> orgs = API.getOrgsCanAdmin(host, this);
        orgs.add(0, owner);
        shareProjectDialog(name, orgs, host, _private_, projectPath);
    }

    protected abstract void shareProjectDialog(String name, List<String> orgs, String host, boolean _private_, String projectPath);

    public void joinWorkspace(final FlooUrl flooUrl, final String path, final boolean upload) {
        FloorcJson floorcJson = null;
        try {
            floorcJson = Settings.get();
        } catch (Throwable e) {
            statusMessage("Invalid JSON in your .floorc.json.");
        }

        HashMap<String, String> auth = floorcJson != null ? floorcJson.auth.get(flooUrl.host) : null;
        if (auth == null) {
            setupHandler(new LinkEditorHandler(this, flooUrl.host, new Runnable() {
                @Override
                public void run() {
                    joinWorkspace(flooUrl, path, upload);
                }
            }));
            return;
        }

        if (!API.workspaceExists(flooUrl, this)) {
            errorMessage(String.format("The workspace %s does not exist!", flooUrl.toString()));
            return;
        }

        if (setupHandler(new FlooHandler(this, flooUrl, upload, path, auth))) {
            return;
        }

        String title = String.format("Really leave %s?", handler.url.workspace);
        String body = String.format("Leave %s and join %s ?", handler.url.toString(), handler.url.toString());
        DialogBuilder.build(title, body, new RunLater<Boolean>() {
            public void run(Boolean join) {
                if (!join) {
                    return;
                }
                shutdown();
                joinWorkspace(flooUrl, path, upload);
            }
        });
    }

    public void createAccount() {
        if (setupHandler(new CreateAccountHandler(this, Constants.defaultHost))) {
            return;
        }
        statusMessage("You already have an account and are connected with it.");
        shutdown();
    }

    public void linkEditor() {
        if (setupHandler(new LinkEditorHandler(this, Constants.defaultHost))) {
            return;
        }
        statusMessage("You already have an account and are connected with it.");
        shutdown();
    }

    private boolean setupHandler(BaseHandler handler) {
        if (isJoined()) {
            return false;
        }

        lock.writeLock().lock();
        this.handler = handler;
        loopGroup = new NioEventLoopGroup();
        lock.writeLock().unlock();
        handler.go();
        return true;
    }

    public boolean isJoined() {
        return handler != null && handler.isJoined;
    }

    public @Nullable
    FlooHandler getFlooHandler(){
        if (handler != null && handler instanceof FlooHandler)
            return (FlooHandler)handler;
        return null;
    }

    public void setColabDir(String colabDir) {
        this.colabDir = colabDir;
        Ignore.writeDefaultIgnores(this);
        refreshIgnores();
    }

    public void refreshIgnores() {
        IFile fileByIoFile = iFactory.findFileByIoFile(new File(colabDir));
        ignoreTree = Ignore.BuildIgnore(fileByIoFile);
    }

    public String absPath(String path) {
        return Utils.absPath(colabDir, path);
    }

    public Boolean isShared(String path) {
        return Utils.isShared(path, colabDir);
    }

    public String toProjectRelPath(String path) {
        return Utils.toProjectRelPath(path, colabDir);
    }

    public Boolean isIgnored(IFile f) {
        String path = f.getPath();

        if (!isShared(path)) {
            Flog.log("Ignoring %s because it isn't shared.", path);
            return true;
        }

        return ignoreTree.isIgnored(f, path, toProjectRelPath(path), f.isDirectory());
    }

    public synchronized void shutdown() {
        lock.writeLock().lock();
        try {
            if (handler != null) {
                handler.shutdown();
                editor.shutdown();
                statusMessage("Disconnecting.");
                handler = null;
            }
            if (iFactory != null) {
                iFactory.clearReadOnlyState();
            }

            if (loopGroup != null) {
                try {
                    loopGroup.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
                } catch (Throwable e) {
                    Flog.warn(e);
                } finally {
                    loopGroup = null;
                }
            }
            ignoreTree = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Ignore getIgnoreTree() {
        return ignoreTree;
    }
    public abstract void loadChatManager();
    public abstract void flashMessage(String message);
    public abstract void warnMessage(String message);
    public abstract void statusMessage(String message);
    public abstract void errorMessage(String message);
    public abstract Object getActualContext();
    protected abstract String selectAccount(String[] keys);
    public abstract void chat(String username, String msg, Date messageDate);
    public abstract void openChat();
    public abstract void listenToEditor(EditorEventHandler editorEventHandler);
    public abstract void setUsers(Map<Integer, FlooUser> users);
    public abstract void setListener(boolean b);
    public abstract void mainThread(final Runnable runnable);
    public abstract void readThread(final Runnable runnable);
    public abstract void writeThread(final Runnable runnable);
}
