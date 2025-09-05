package lu212.statsclient;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;


import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.ServiceLoader;
import java.util.concurrent.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lu212.sysstatz.client.api.SysStatsPlugin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class Client {

    private static String SERVER_HOST = "127.0.0.1";
    private static int SERVER_PORT = 12345;
    private static final String CONFIG_FILE = "config.txt";

    private static String clientName;
    private static String allowSCMD;
    private static String serverPass;
    
    private SSLSocket socket;
    private BufferedReader in;
    private PrintWriter out;

    private SystemInfo systemInfo;
    private HardwareAbstractionLayer hal;
    private CentralProcessor processor;
    private GlobalMemory memory;
    private Sensors sensors;
    private FileSystem fileSystem;
    
    private long[][] oldTicks;
    
    private static int MONITORING_INTERVAL = 20;

    private volatile long[] prevTicks = null;
    private ScheduledExecutorService scheduler;
    
    private long prevSent = -1;
    private long prevRecv = -1;
    
    private OperatingSystem os;
    private Map<Integer, OSProcess> oldProcesses = new ConcurrentHashMap<>();
    

    private Map<String, SysStatsPlugin> loadedPlugins = new HashMap<>();
    

    public static void main(String[] args) {
        System.out.println("SysStats Client - Beta 0.8.0 - Build: 2025-07-31");

        loadOrSetupConfig();

        Client client = new Client();
            client.loadOrCreateName();
            try {
				client.runWithReconnect();
			} catch (IOException e) {
				e.printStackTrace();
			}
    }

    
    private static void loadOrSetupConfig() {
        File config = new File(CONFIG_FILE);

        if (config.exists()) {
            // Konfigurationsdatei existiert → laden
            try (Scanner fileScanner = new Scanner(config)) {
                SERVER_HOST = fileScanner.nextLine().trim();
                SERVER_PORT = Integer.parseInt(fileScanner.nextLine().trim());
                if (fileScanner.hasNextLine()) {
                    MONITORING_INTERVAL = Integer.parseInt(fileScanner.nextLine().trim());
                } else {
                    MONITORING_INTERVAL = 5000; // Standardintervall (ms)
                    System.err.println("[!] Kein Intervall gefunden, Standardwert (5000 ms) gesetzt.");
                }
                serverPass = fileScanner.nextLine().trim();
                allowSCMD = fileScanner.nextLine().trim();

                System.out.println("[✓] Konfiguration geladen: " + SERVER_HOST + ":" + SERVER_PORT +
                                   " | Client: " + clientName + " | Intervall: " + MONITORING_INTERVAL + " s |" +
                                   " AllowSCMD: " + allowSCMD);
            } catch (Exception e) {
                System.err.println("[!] Fehler beim Laden – Konfiguration wird neu erstellt.");
                config.delete(); // defekte Datei löschen
                loadOrSetupConfig(); // Setup neu starten
            }
        } else {
            // Setup durchführen
            Scanner sc = new Scanner(System.in);
            System.out.println("╔════════════════════════════════╗");
            System.out.println("║             SETUP              ║");
            System.out.println("╚════════════════════════════════╝");

            System.out.print("→ Main-Server-IP eingeben: ");
            SERVER_HOST = sc.nextLine().trim();

            System.out.print("→ Main-Server-Port eingeben: ");
            while (!sc.hasNextInt()) {
                System.out.print("Bitte gültige Portnummer eingeben: ");
                sc.next();
            }
            SERVER_PORT = sc.nextInt();
            sc.nextLine(); // Rest der Zeile weg

            System.out.print("→ Stats-Update Intervall in Sekunden eingeben: ");
            while (!sc.hasNextInt()) {
                System.out.print("Bitte gültige Zahl eingeben: ");
                sc.next();
            }
            MONITORING_INTERVAL = sc.nextInt();
            sc.nextLine(); // Rest der Zeile weg

            System.out.print("→ Main-Server Passwort: ");
            serverPass = sc.nextLine().trim();

            System.out.print("→ Darf der Hauptserver diesen Server neustarten/stoppen (true/false): ");
            boolean ready = false;
            while (!ready) {
                allowSCMD = sc.nextLine().trim();
                if (allowSCMD.equalsIgnoreCase("true") || allowSCMD.equalsIgnoreCase("false")) {
                    ready = true;
                } else {
                    System.out.println("Gebe true oder false ein.");
                }
            }

            // Datei erstellen und speichern
            try (PrintWriter writer = new PrintWriter(config)) {
                writer.println(SERVER_HOST);
                writer.println(SERVER_PORT);
                writer.println(MONITORING_INTERVAL);
                writer.println(serverPass);
                writer.println(allowSCMD);
                System.out.println("[✓] Konfiguration gespeichert in \"" + CONFIG_FILE + "\"");
            } catch (IOException e) {
                System.err.println("[!] Fehler beim Schreiben der Konfigurationsdatei.");
            }

            System.out.println("╔════════════════════════════════╗");
            System.out.println("║       SETUP ABGESCHLOSSEN      ║");
            System.out.println("╚════════════════════════════════╝");
        }
    }

    public void startMonitoring() {
        System.out.println("Starte Monitoring...");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SystemStats-Scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendSystemStats();
            } catch (Exception e) {
                System.err.println("Fehler im Monitoring-Task:");
                e.printStackTrace();
            }
        }, 0, MONITORING_INTERVAL, TimeUnit.SECONDS);
    }

    public void stopMonitoring() {
        System.out.println("Stoppe Monitoring...");
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendSystemStats() {
        System.out.println("Sende Systemstats...");

        System.out.println("--------------------------------");
        
        sendProcessStats();
        
    	System.out.println("Sende Konfiguration...");
    	sendMessage("SERVER:SCMD " + allowSCMD);
        
        List<NetworkIF> networkIFs = hal.getNetworkIFs();
        long totalBytesSent = 0;
        long totalBytesRecv = 0;

        for (NetworkIF net : networkIFs) {
            net.updateAttributes(); // sehr wichtig, um aktuelle Werte zu erhalten
            totalBytesSent += net.getBytesSent();
            totalBytesRecv += net.getBytesRecv();
        }

        sendMessage(String.format("SERVER:NET_SENT %d", totalBytesSent));
        sendMessage(String.format("SERVER:NET_RECV %d", totalBytesRecv));
        System.out.printf("Netzwerk gesendet: %.2f MB, empfangen: %.2f MB%n",
            totalBytesSent / (1024.0 * 1024), totalBytesRecv / (1024.0 * 1024));

        
        for (NetworkIF net : networkIFs) {
            net.updateAttributes();
            totalBytesSent += net.getBytesSent();
            totalBytesRecv += net.getBytesRecv();
        }

        if (prevSent >= 0 && prevRecv >= 0) {
            long deltaSent = totalBytesSent - prevSent;
            long deltaRecv = totalBytesRecv - prevRecv;

            sendMessage(String.format("SERVER:NET_DELTA_SENT %d", deltaSent));
            sendMessage(String.format("SERVER:NET_DELTA_RECV %d", deltaRecv));
            System.out.printf("Netzwerk seit letztem Intervall: ↑ %.2f kB, ↓ %.2f kB%n",
                deltaSent / 1024.0, deltaRecv / 1024.0);
        }

        prevSent = totalBytesSent;
        prevRecv = totalBytesRecv;

        
        if (prevTicks == null) {
            prevTicks = processor.getSystemCpuLoadTicks();
            System.out.println("Initiale CPU-Ticks gespeichert, CPU Load = 0.00%");
        } else {
            double cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
            prevTicks = processor.getSystemCpuLoadTicks();

            sendMessage(String.format("SERVER:CPU %.2f", cpuLoad));
            System.out.printf("CPU Load: %.2f%%%n", cpuLoad);
        }

        long totalMem = memory.getTotal();
        long availableMem = memory.getAvailable();
        long usedMem = totalMem - availableMem;
        long swapTotal = memory.getVirtualMemory().getSwapTotal();
        long swapUsed = memory.getVirtualMemory().getSwapUsed();

        sendMessage(String.format("SERVER:RAM_USED_MB %d", usedMem / (1024 * 1024)));
        sendMessage(String.format("SERVER:RAM_AVAILABLE_MB %d", availableMem / (1024 * 1024)));
        sendMessage(String.format("SERVER:RAM_TOTAL_MB %d", totalMem / (1024 * 1024)));
        sendMessage(String.format("SERVER:SWAP_USED_MB %d", swapUsed / (1024 * 1024)));
        sendMessage(String.format("SERVER:SWAP_TOTAL_MB %d", swapTotal / (1024 * 1024)));

        System.out.printf("RAM: verwendet: %d MB, verfügbar: %d MB, gesamt: %d MB%n",
            usedMem / (1024 * 1024), availableMem / (1024 * 1024), totalMem / (1024 * 1024));
        System.out.printf("SWAP: verwendet: %d MB, gesamt: %d MB%n",
            swapUsed / (1024 * 1024), swapTotal / (1024 * 1024));

        List<OSFileStore> fsList = fileSystem.getFileStores();

        long totalSpaceBytes = 0;
        long usableSpaceBytes = 0;

        for (OSFileStore store : fsList) {
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();

                if (total > 0) {
                    totalSpaceBytes += total;
                    usableSpaceBytes += usable;
                }
        }

        long totalGB = totalSpaceBytes / (1024L * 1024 * 1024);
        long usedGB = (totalSpaceBytes - usableSpaceBytes) / (1024L * 1024 * 1024);

        sendMessage(String.format("SERVER:DISKUSAGE %d/%d", usedGB, totalGB));
        System.out.printf("Gesamte Festplattennutzung: %d/%d GB belegt/gesamt%n", usedGB, totalGB);

        double cpuTemp = sensors.getCpuTemperature();
        if (cpuTemp > 0) {
        	sendMessage(String.format("SERVER:TEMP %.1f", cpuTemp));
            System.out.printf("CPU Temperatur: %.1f°C%n", cpuTemp);
        } else {
        	sendMessage("SERVER:TEMP 0");
            System.out.println("CPU Temperatur nicht verfügbar.");
        }

        int[] fanSpeeds = sensors.getFanSpeeds();
        if (fanSpeeds.length > 0) {
            sendMessage(String.format("SERVER:FAN %d", fanSpeeds[0]));
            System.out.printf("Lüfterdrehzahl: %d RPM%n", fanSpeeds[0]);
        } else {
            System.out.println("Lüfterdrehzahlen nicht verfügbar.");
        }

        double cpuVoltage = sensors.getCpuVoltage();
        if (cpuVoltage > 0) {
            sendMessage(String.format("SERVER:CPUVOLTAGE %.2f", cpuVoltage));
            System.out.printf("CPU Spannung: %.2f V%n", cpuVoltage);
        } else {
            System.out.println("CPU Spannung nicht verfügbar.");
        }
        double[] loadPerCore = processor.getProcessorCpuLoadBetweenTicks(oldTicks);
        long[] freq = processor.getCurrentFreq();

        for (int i = 0; i < loadPerCore.length; i++) {
            double coreLoad = loadPerCore[i] * 100;
            double ghz = (i < freq.length) ? freq[i] / 1.0e9 : 0.0;
            sendMessage(String.format("SERVER:CPU_CORE%d_LOAD %.2f", i, coreLoad));
            sendMessage(String.format("SERVER:CPU_CORE%d_FREQ %.2f", i, ghz));
            System.out.println(i + ", " + ghz);
        }

        System.out.println("Sende Systemstats abgeschlossen.");
        System.out.println("--------------------------------");
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        } else {
            System.err.println("Nicht verbunden, Nachricht nicht gesendet.");
        }
    }

    private void readMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("Server: " + line);
                if (line.equalsIgnoreCase("SCMD:SHUTDOWN") && "true".equalsIgnoreCase(allowSCMD)) {
                    System.out.println("Fahre herunter …");
                    if (!shutdownNow()) System.err.println("Shutdown fehlgeschlagen (Berechtigungen?)");
                }

                if (line.equalsIgnoreCase("SCMD:REBOOT") && "true".equalsIgnoreCase(allowSCMD)) {
                    System.out.println("Starte neu …");
                    if (!rebootNow()) System.err.println("Reboot fehlgeschlagen (Berechtigungen?)");
                }
                }
            // Wenn readLine null zurückgibt, Verbindung wurde geschlossen
            System.out.println("Server hat Verbindung geschlossen.");
        } catch (IOException e) {
            System.out.println("Verbindung zum Server verloren: " + e.getMessage());
        }

        // Verbindung verloren → reconnect starten
        stopMonitoring();
        close();
    }
    
    private boolean exec(List<String> cmd) {
        System.out.println("-> Exec: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = r.readLine()) != null) System.out.println(s);
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("Prozess lief zu lange, abgebrochen.");
                return false;
            }
            int code = p.exitValue();
            System.out.println("<- Exitcode: " + code);
            return code == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }
    private boolean isMac() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("mac");
    }
    private boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nix") || os.contains("nux") || os.contains("aix");
    }

    private boolean shutdownNow() {
        if (isWindows()) {
            return exec(List.of("shutdown", "-s", "-t", "0"));
        } else if (isMac()) {
            // 1) ohne Root: AppleScript (GUI)
            if (exec(List.of("osascript", "-e",
                    "tell application \"System Events\" to shut down"))) return true;
            // 2) mit Root via sudo (passwortlos per sudoers)
            return exec(List.of("sudo", "-n", "/sbin/shutdown", "-h", "now"))
                || exec(List.of("sudo", "-n", "/usr/sbin/shutdown", "-h", "now"));
        } else if (isLinux()) {
            // bevorzugt systemd
            return exec(List.of("sudo", "-n", "/usr/bin/systemctl", "poweroff"))
                || exec(List.of("sudo", "-n", "/bin/systemctl", "poweroff"))
                || exec(List.of("sudo", "-n", "/sbin/shutdown", "-h", "now"))
                || exec(List.of("sudo", "-n", "/usr/sbin/shutdown", "-h", "now"));
        }
        System.err.println("OS nicht erkannt – kein Shutdown ausgeführt.");
        return false;
    }

    private boolean rebootNow() {
        if (isWindows()) {
            return exec(List.of("shutdown", "-r", "-t", "0"));
        } else if (isMac()) {
            if (exec(List.of("osascript", "-e",
                    "tell application \"System Events\" to restart"))) return true;
            return exec(List.of("sudo", "-n", "/sbin/reboot"))
                || exec(List.of("sudo", "-n", "/usr/sbin/reboot"));
        } else if (isLinux()) {
            return exec(List.of("sudo", "-n", "/usr/bin/systemctl", "reboot"))
                || exec(List.of("sudo", "-n", "/bin/systemctl", "reboot"))
                || exec(List.of("sudo", "-n", "/sbin/reboot"))
                || exec(List.of("sudo", "-n", "/usr/sbin/reboot"));
        }
        System.err.println("OS nicht erkannt – kein Reboot ausgeführt.");
        return false;
    }

    private void close() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
            socket = null;
            in = null;
            out = null;
            System.out.println("Client beendet.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadOrCreateName() {
        clientName = System.getenv("COMPUTERNAME"); // Windows
        if (clientName == null || clientName.isBlank()) {
            clientName = System.getenv("HOSTNAME"); // Linux/Mac
        }

        if (clientName == null || clientName.isBlank()) {
            try {
                clientName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                clientName = "DefaultClient";
            }
        }

        System.out.println("Verwende Clientname: " + clientName);
    }
    
    private void connectAndStart() throws Exception {
        systemInfo = new SystemInfo();
        os = systemInfo.getOperatingSystem();

        // SSL initialisieren
        SSLContext sslContext = SSLContext.getDefault();
        SSLSocketFactory ssf = sslContext.getSocketFactory();
        socket = createUnsecureSSLSocket(SERVER_HOST, SERVER_PORT);
        socket.startHandshake();

        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);

        // Handshake: Name
        String serverMsg = in.readLine();
        if (!"GIB_NAME".equals(serverMsg)) {
            close();
            throw new IOException("Unerwartete Serverantwort: " + serverMsg);
        }
        out.println(clientName);

        serverMsg = in.readLine();
        if ("NAME_UNGUELTIG".equals(serverMsg)) {
            close();
            throw new IOException("Name ungültig oder bereits vergeben.");
        } else if (!"NAME_ANGEKOMMEN".equals(serverMsg)) {
            close();
            throw new IOException("Unerwartete Serverantwort: " + serverMsg);
        }

        // Handshake: Passwort
        serverMsg = in.readLine();
        if (!"GIB_PASSWORT".equals(serverMsg)) {
            close();
            throw new IOException("Unerwartete Serverantwort: " + serverMsg);
        }

        // Passwort aus Config oder Konstante laden
        String password = loadPasswordFromConfig(); 
        out.println(password);

        serverMsg = in.readLine();
        if ("PASSWORT_FALSCH".equals(serverMsg)) {
            close();
            throw new IOException("Passwort falsch, Verbindung beendet.");
        } else if (!"PASSWORT_OK".equals(serverMsg)) {
            close();
            throw new IOException("Unerwartete Serverantwort: " + serverMsg);
        }

        System.out.println("Verbunden mit Server als " + clientName);
        
        Path pluginDir = Paths.get("plugins");
        if (!Files.exists(pluginDir)) {
            Files.createDirectories(pluginDir);
            System.out.println("Plugin Ordner erstellt.");
        } else {
        	System.out.println("Plugin Ordner Existiert.");
        }
        
        System.out.println("Lade PLugins...");
        loadAndStartPlugins(Paths.get("plugins"));

        // Hardware initialisieren
        hal = systemInfo.getHardware();
        processor = hal.getProcessor();
        memory = hal.getMemory();
        sensors = hal.getSensors();
        fileSystem = os.getFileSystem();
        oldTicks = processor.getProcessorCpuLoadTicks();

        sendHardwareInfo();
        startMonitoring();

        // Thread zum Lesen der Server-Nachrichten
        Thread readerThread = new Thread(this::readMessages, "Server-Reader-Thread");
        readerThread.setDaemon(true);
        readerThread.start();
        
        while (in.readLine() != null) {
        }
        // Verbindung verloren
        stopMonitoring();
        stopAllPlugins();
        close();

    }

    private void sendProcessStats() {
        System.out.println("Sende Prozessdaten...");

        List<OSProcess> allProcesses = os.getProcesses();

        allProcesses.sort((p1, p2) -> Double.compare(
                p2.getProcessCpuLoadCumulative(),
                p1.getProcessCpuLoadCumulative()
        ));

        int max = Math.min(5, allProcesses.size());
        for (int i = 0; i < max; i++) {
            OSProcess process = allProcesses.get(i);
            
            //Prozess "Idle" überspüringen
            if ("Idle".equalsIgnoreCase(process.getName())) {
                continue;
            }
            
            OSProcess oldProcess = oldProcesses.get(process.getProcessID());

            double cpuLoad;
            if (oldProcess != null) {
                cpuLoad = process.getProcessCpuLoadBetweenTicks(oldProcess) * 100d;
                if (cpuLoad < 0) cpuLoad = 0;
                if (cpuLoad > 100) cpuLoad = 100; // oder ein max. Grenzwert setzen
            } else {
                cpuLoad = 0.0;
            }


            String name = process.getName();
            int pid = process.getProcessID();
            long memoryUsedMB = process.getResidentSetSize() / (1024 * 1024);

            sendMessage(String.format("SERVER:PROC %d %s %.2f %.2f",
                    pid,
                    name.replaceAll(" ", "_"),
                    cpuLoad,
                    memoryUsedMB / 1024.0 // RAM in GB
            ));

            System.out.printf("  → %s (PID %d): CPU %.2f%%, RAM: %d MB%n",
                    name, pid, cpuLoad, memoryUsedMB);
        }

        // Alte Prozesse für nächstes Intervall speichern
        oldProcesses.clear();
        for (OSProcess p : allProcesses) {
            oldProcesses.put(p.getProcessID(), p);
        }

        System.out.println("Prozessdaten gesendet.");
    }
    
    private void sendHardwareInfo() {
        System.out.println("Sende Hardwareinfo...");

        // Betriebssystem-Informationen
        String osName = os.getFamily();  // Betriebssystemname (z.B. Windows, Linux, macOS)
        String osVersion = os.getVersionInfo().getVersion();  // Betriebssystemversion
        String osArch = System.getProperty("os.arch");  // Architektur (z.B. x86_64, ARM)
        
        sendMessage(String.format("HW:OS %s %s %s", osName, osVersion, osArch));
        System.out.println("Betriebssystem: " + osName + " " + osVersion + " " + osArch);
        
        // CPU
        String cpuModel = processor.getProcessorIdentifier().getName();
        sendMessage("HW:CPU " + cpuModel);
        System.out.println("CPU: " + cpuModel);

        // Motherboard
        String manufacturer = hal.getComputerSystem().getBaseboard().getManufacturer();
        String model = hal.getComputerSystem().getBaseboard().getModel();
        sendMessage(String.format("HW:MAINBOARD %s %s", manufacturer, model));
        System.out.println("Mainboard: " + manufacturer + " " + model);

        // RAM-Module
        List<oshi.hardware.PhysicalMemory> memoryModules = hal.getMemory().getPhysicalMemory();
        for (int i = 0; i < memoryModules.size(); i++) {
            var mem = memoryModules.get(i);
            sendMessage(String.format("HW:RAM_MODULE_%d %s %.2f GB", i + 1, mem.getMemoryType(), mem.getCapacity() / (1024.0 * 1024 * 1024)));
            System.out.printf("RAM-Modul %d: %s, %.2f GB%n", i + 1, mem.getMemoryType(), mem.getCapacity() / (1024.0 * 1024 * 1024));
        }

        // Festplatten
        List<oshi.hardware.HWDiskStore> disks = hal.getDiskStores();
        for (int i = 0; i < disks.size(); i++) {
            var disk = disks.get(i);
            sendMessage(String.format("HW:DISK_%d %s %.2f GB", i + 1, disk.getModel(), disk.getSize() / (1024.0 * 1024 * 1024)));
            System.out.printf("Disk %d: %s, %.2f GB%n", i + 1, disk.getModel(), disk.getSize() / (1024.0 * 1024 * 1024));
        }

        // Netzwerkkarten
        List<NetworkIF> nics = hal.getNetworkIFs();
        for (int i = 0; i < nics.size(); i++) {
            var nic = nics.get(i);
            sendMessage(String.format("HW:NET_%d %s (%s)", i + 1, nic.getDisplayName(), nic.getMacaddr()));
            System.out.printf("Netzwerkadapter %d: %s (%s)%n", i + 1, nic.getDisplayName(), nic.getMacaddr());
        }
        
        String geoJson = getGeoLocationInfo();
        if (geoJson != null) {
            sendMessage("GEO:" + geoJson); // Optional: vorher noch parsen & nur relevante Teile senden
            System.out.println("GeoLocation-Daten gesendet.");
        } else {
            System.out.println("GeoLocation-Daten konnten nicht ermittelt werden.");
        }

        System.out.println("Hardwareinfo gesendet.");
    }
    
    public static String getGeoLocationInfo() {
        try {
            URL url = new URL("http://ip-api.com/json");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }

            in.close();
            con.disconnect();

            return content.toString(); // JSON-String z.B. {"country":"Germany", "lat":...}
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public void runWithReconnect() throws IOException {
        while (true) {
            try {
                connectAndStart();
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("Verbindung verloren. Neuer Verbindungsversuch in 5 Sekunden...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private String loadPasswordFromConfig() {
    	return serverPass;
    }
    
    private SSLSocket createUnsecureSSLSocket(String host, int port) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");

        sslContext.init(null, new javax.net.ssl.TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }
        }, new java.security.SecureRandom());

        SSLSocketFactory ssf = sslContext.getSocketFactory();
        return (SSLSocket) ssf.createSocket(host, port);
    }
    
    private void loadAndStartPlugins(Path pluginFolder) {
        System.out.println("[DEBUG] Starte Plugin-Ladeprozess für Ordner: " + pluginFolder.toAbsolutePath());
        System.out.flush();

        File[] jars = pluginFolder.toFile().listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.out.println("[DEBUG] Keine JAR-Dateien im Plugin-Ordner gefunden.");
            return;
        }

        System.out.println("[DEBUG] Gefundene JARs: " + jars.length);
        for (File jar : jars) System.out.println(" - " + jar.getName());
        System.out.flush();

        try {
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) {
                urls.add(jar.toURI().toURL());
                System.out.println("[DEBUG] URL hinzugefügt: " + jar.toURI().toURL());
            }

            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), this.getClass().getClassLoader());
            System.out.println("[DEBUG] ClassLoader erstellt.");
            System.out.flush();

            ServiceLoader<SysStatsPlugin> plugins = ServiceLoader.load(SysStatsPlugin.class, loader);
            int loadedCount = 0;

            for (SysStatsPlugin plugin : plugins) {
                try {
                    System.out.println("[DEBUG] Plugin gefunden: " + plugin.getName());
                    loadedPlugins.put(plugin.getName(), plugin);
                    plugin.start((key, value) -> sendToServer(plugin.getName(), key, value));
                    System.out.println("[DEBUG] Plugin gestartet: " + plugin.getName());
                    loadedCount++;
                } catch (Throwable t) {
                    System.err.println("[ERROR] Fehler beim Laden/Starten des Plugins: " + plugin.getClass().getName());
                    t.printStackTrace();
                }
                System.out.flush();
            }

            System.out.println("[DEBUG] Plugin-Ladeprozess abgeschlossen. Plugins geladen: " + loadedCount);
            System.out.flush();

        } catch (Throwable e) {
            System.err.println("[ERROR] Fehler beim Erstellen des ClassLoaders oder beim Laden der Plugins:");
            e.printStackTrace();
            System.out.flush();
        }
    }

    private void sendToServer(String pluginName, String key, String value) {
        if (out != null) {
            out.println("PLUGIN:" + key + " " + value);
        }
    }
    
    private void stopAllPlugins() {
        for (SysStatsPlugin plugin : loadedPlugins.values()) {
            try {
                plugin.stop();
                System.out.println("Plugin gestoppt: " + plugin.getName());
            } catch (Exception e) {
                System.err.println("Fehler beim Stoppen von " + plugin.getName() + ": " + e.getMessage());
            }
        }
        loadedPlugins.clear();
    }
}
