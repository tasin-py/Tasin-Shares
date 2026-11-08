package com.tasin.shares;

import android.content.Context;
import android.os.Environment;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** FTP on the configured port (passive data on the nine ports after it), one user "tv" rooted at internal storage. */
final class Ftp {
    private FtpServer server;

    synchronized void start(Context ctx, Prefs prefs) throws Exception {
        if (server != null && !server.isStopped()) return;
        PropertiesUserManagerFactory umf = new PropertiesUserManagerFactory();
        File users = new File(ctx.getFilesDir(), "ftpusers.properties");
        // Start from an empty file (FtpServer refuses a missing one); the user is written below with the current password.
        users.delete();
        users.createNewFile();
        umf.setFile(users);
        umf.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        UserManager um = umf.createUserManager();

        BaseUser user = new BaseUser();
        user.setName(Prefs.FTP_USER);
        user.setPassword(prefs.password());
        user.setHomeDirectory(Environment.getExternalStorageDirectory().getAbsolutePath());
        user.setMaxIdleTime(0);
        List<Authority> auth = new ArrayList<>();
        auth.add(new WritePermission());
        auth.add(new ConcurrentLoginPermission(10, 10));
        auth.add(new TransferRatePermission(0, 0));
        user.setAuthorities(auth);
        um.save(user);

        ListenerFactory lf = new ListenerFactory();
        lf.setPort(prefs.ftpPort());
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts((prefs.ftpPort() + 1) + "-" + (prefs.ftpPort() + 9));
        lf.setDataConnectionConfiguration(dc.createDataConnectionConfiguration());

        FtpServerFactory sf = new FtpServerFactory();
        sf.addListener("default", lf.createListener());
        sf.setUserManager(um);
        server = sf.createServer();
        server.start();
    }

    synchronized void stop() {
        if (server != null) server.stop();
        server = null;
    }

    synchronized boolean running() {
        return server != null && !server.isStopped();
    }
}
