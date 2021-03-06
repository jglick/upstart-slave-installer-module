package org.jenkinsci.modules.upstart_slave_installer;

import hudson.Util;
import hudson.os.SU;
import hudson.remoting.Callable;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.modules.slave_installer.AbstractUnixSlaveInstaller;
import org.jenkinsci.modules.slave_installer.InstallationException;
import org.jenkinsci.modules.slave_installer.LaunchConfiguration;
import org.jenkinsci.modules.slave_installer.Prompter;
import org.jvnet.localizer.Localizable;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Performs slave installation via upstart.
 *
 * @author Kohsuke Kawaguchi
 */
public class UpstartSlaveInstaller extends AbstractUnixSlaveInstaller {
    private final String instanceId;

    public UpstartSlaveInstaller(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public Localizable getConfirmationText() {
        return Messages._UpstartSlaveInstaller_ConfirmationText();
    }

    @Override
    public void install(LaunchConfiguration params, Prompter prompter) throws InstallationException, IOException, InterruptedException {
        final File srcSlaveJar = params.getJarFile();
        final String args = params.buildRunnerArguments().toStringWithQuote();
        final File rootDir = params.getStorage().getAbsoluteFile();
        final StreamTaskListener listener = StreamTaskListener.fromStdout();
        final String userName = getCurrentUnixUserName();

        String rootUser = prompter.prompt("Specify the super user name to 'sudo' to","root");
        String rootPassword = prompter.promptPassword("Specify your password for sudo (or empty if you can sudo without password)");

        SU.execute(listener,rootUser,rootPassword,new Callable<Void, IOException>() {
            public Void call() throws IOException {
                try {
                    File slaveJar = new File(rootDir, "slave.jar");
                    FileUtils.copyFile(srcSlaveJar, slaveJar);

                    String conf = IOUtils.toString(getClass().getResourceAsStream("jenkins-slave.conf"));
                    conf = conf
                            .replace("{username}", userName)
                            .replace("{jar}", slaveJar.getAbsolutePath())
                            .replace("{args}", args);

                    final String name = "jenkins-slave-" + instanceId;  // service name
                    FileUtils.writeStringToFile(new File("/etc/init/"+name+".conf"), conf);

                    Util.createSymlink(new File("/etc/init.d"), "/lib/init/upstart-job", name, listener);

                    Process p = new ProcessBuilder("initctl","start",name).redirectErrorStream(true).start();
                    p.getOutputStream().close();
                    IOUtils.copy(p.getInputStream(),listener.getLogger());

                    int r = p.waitFor();
                    if (r!=0) // error, but too late to recover
                        throw new IOException("Failed to launch  a service: " + r);

                    return null;
                } catch (InterruptedException e) {
                    throw (InterruptedIOException)new InterruptedIOException().initCause(e);
                }
            }
        });

        System.exit(0);
    }
}
