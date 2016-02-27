package com.zijingcloud.testonsite;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JApplet;
import javax.swing.JFileChooser;
import javax.swing.JRootPane;
import javax.swing.UIManager;
import com.zijingcloud.testonsite.checkers.Checker;
import com.zijingcloud.testonsite.checkers.CheckerWorker;
import com.zijingcloud.testonsite.checkers.HTTPConnectivityChecker;
import com.zijingcloud.testonsite.checkers.LDAPConnectivityChecker;
import com.zijingcloud.testonsite.checkers.RTCPConnectivityChecker;
import com.zijingcloud.testonsite.checkers.RTPConnectivityChecker;
import com.zijingcloud.testonsite.checkers.SIPConnectivityChecker;
import com.zijingcloud.testonsite.js.Caller;
import com.zijingcloud.testonsite.js.JSCaller;
import com.zijingcloud.testonsite.js.JSInvoker;
import org.apache.log4j.Logger;

public class TestOnSiteApplet extends JApplet
{
  private static final long serialVersionUID = 358280086206988551L;
  private static final int APP_PREFERRED_WIDTH = 1;
  private static final int APP_PREFERRED_HEIGHT = 1;
  private static Logger log = Logger.getLogger("AdminApplet");
  private final List<Checker> checkers;
  private final Caller js;
  private final ReportGenerator reportGen;
  private CheckerWorker worker;
  private String report;
  private String appletVersion;

  public TestOnSiteApplet()
  {
    this.checkers = new ArrayList();
    this.js = new JSCaller(this);
    this.reportGen = new ReportGenerator();

    this.worker = null;

    this.appletVersion = "2016-02-01/23"; 
  }

  public void init() {
    log.info("OS   name is: " + System.getProperty("os.name"));
    log.info("OS   arch is: " + System.getProperty("os.arch"));
    log.info("OS   version is: " + System.getProperty("os.version"));
    log.info("Java vendor is: " + System.getProperty("java.vendor"));
    log.info("Java version is: " + System.getProperty("java.version"));

    log.info("Deployed date version: " + this.appletVersion);
    this.reportGen.setAppletVersion(this.appletVersion);

    createDebugUI();
    createCheckers();
    this.js.appletReady();
  }

  private void createDebugUI()
  {
    String blendColor = getParameter("blend");
    try
    {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    }
    catch (Exception e) {
    }
    if (blendColor != null) {
      Canvas canvas = new Canvas();
      try {
        canvas.setBackground(Color.decode(blendColor));
      } catch (NumberFormatException e) {
        canvas.setBackground(Color.WHITE);
      }
      setGlassPane(canvas);
      canvas.setVisible(true);
    }

    setPreferredSize(new Dimension(1, 1));
  }

  public void toggleHideApplet() {
    Component glass = getRootPane().getGlassPane();
    if (glass.isVisible()) {
      glass.setVisible(false);
      repaint();
    } else {
      setVisible(true);
    }
  }

  private void createCheckers() {
    this.checkers.add(new HTTPConnectivityChecker("HTTP-provision", this.js, "/prov/echo/"));
    this.checkers.add(new HTTPConnectivityChecker("HTTP-phonebook", this.js, "/phonebook/echo/"));
    this.checkers.add(new LDAPConnectivityChecker("LDAP-phonebook", this.js));
    this.checkers.add(new SIPConnectivityChecker("SIP", this.js));
    this.checkers.add(new RTCPConnectivityChecker("RTCP", this.js));
    this.checkers.add(new RTPConnectivityChecker("Bandwidth-minimum", this.js).setBW(384));
    this.checkers.add(new RTPConnectivityChecker("Bandwidth-basic", this.js).setBW(768));
    this.checkers.add(new RTPConnectivityChecker("Bandwidth-pc", this.js).setBW(1250));
    this.checkers.add(new RTPConnectivityChecker("Bandwidth-plus", this.js).setBW(1472));
    this.checkers.add(new RTPConnectivityChecker("Bandwidth-premium", this.js).setBW(2560));
  }

  public void js_setHosts(String provHost, String pbHost, String mediaHost, String sipHost) {
    ((Checker)this.checkers.get(0)).setHost(provHost);
    ((Checker)this.checkers.get(1)).setHost(pbHost);
    ((Checker)this.checkers.get(2)).setHost("ldap.myvmr.cn");
    ((Checker)this.checkers.get(3)).setHost(sipHost);
    ((Checker)this.checkers.get(4)).setHost(mediaHost);
    ((Checker)this.checkers.get(5)).setHost(mediaHost);
    ((Checker)this.checkers.get(6)).setHost(mediaHost);
    ((Checker)this.checkers.get(7)).setHost(mediaHost);
    ((Checker)this.checkers.get(8)).setHost(mediaHost);
    ((Checker)this.checkers.get(9)).setHost(mediaHost);

    this.reportGen.setHosts(pbHost, provHost, sipHost, mediaHost);
  }

  private boolean startCheckers() {
    log.debug("Asked to start checkers: " + Thread.currentThread());
    synchronized (this.reportGen) {
      this.reportGen.clear();
      this.reportGen.started();
    }

    stopCheckers();
    this.worker = new CheckerWorker(this.js, this.checkers);
    this.worker.start();
    return true;
  }

  public void js_startCheckers() {
    JSInvoker.invoke(new Runnable() {
      public void run() {
        startCheckers();
      }
    });
  }

  public void js_stopCheckers() {
    JSInvoker.invoke(new Runnable() {
      public void run() {
        stopCheckers();
      }
    });
  }

  public void js_saveReport()
  {
    JSInvoker.invoke(new Runnable() {
      public void run() {
        saveReport();
      }
    });
  }

  private void stopCheckers() {
    if (this.worker != null) {
      log.debug("Asked to stop current checker");
      this.worker.abort(2000L);
      this.worker = null;
    }
  }

  public void destroy() {
    log.debug("Time to say goodbye...");
    stopCheckers();
  }

  public void checkerInfo(String name, String msg) {
    synchronized (this.reportGen) {
      this.reportGen.checkerInfo(name, msg);
    }
  }

  public void checkerError(String name, String msg) {
    synchronized (this.reportGen) {
      this.reportGen.checkerError(name, msg);
    }
  }

  public String checkersDone() {
    synchronized (this.reportGen) {
      this.reportGen.ended();
    }

    this.report = this.reportGen.generateReport();
    return this.report;
  }

  private void saveReport() {
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new File(this.reportGen.suggestFilename()));

    if (chooser.showSaveDialog(this) == 0)
      try {
        FileWriter writer = new FileWriter(chooser.getSelectedFile());
        writer.write(this.report);
        writer.flush();
        writer.close();
      }
      catch (IOException e)
      {
      }
  }
}
