import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ChatClientPC {
    private static String username;
    private static Socket socket;
    private static volatile boolean running = true;
    private static volatile PendingFileOffer pendingOffer = null;

    private static class PendingFileOffer {
        String sender;
        String fileName;
        long fileSize;
        String hostIp;
        int filePort;

        PendingFileOffer(String sender, String fileName, long fileSize, String hostIp, int filePort) {
            this.sender = sender;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.hostIp = hostIp;
            this.filePort = filePort;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Dynamic Server Connection Setup
        System.out.print("Enter Server IP (Press Enter for 127.0.0.1): ");
        String hostInput = scanner.nextLine().trim();
        String serverHost = hostInput.isEmpty() ? "127.0.0.1" : hostInput;

        System.out.print("Enter Server Port (Press Enter for 12345): ");
        String portInput = scanner.nextLine().trim();
        int serverPort = 12345;
        if (!portInput.isEmpty()) {
            try {
                serverPort = Integer.parseInt(portInput);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port. Using default 12345.");
            }
        }

        try {
            socket = new Socket(serverHost, serverPort);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            if ("ENTER_NAME".equals(in.readLine())) {
                System.out.print("Enter your username: ");
                username = scanner.nextLine().trim();
                out.println(username);
            }

            System.out.println("\n=== Connected to Chat Room ===");
            System.out.println("Commands:");
            System.out.println("  Type text to message normally.");
            System.out.println("  /s <filepath>  (or /sendfile) -> Offer a file to everyone.");
            System.out.println("  /q             (or /exit)     -> Leave chat gracefully.");
            System.out.println("===============================\n");

            // Incoming message reader thread
            new Thread(() -> {
                try {
                    String serverMessage;
                    while (running && (serverMessage = in.readLine()) != null) {
                        if ("SERVER_SHUTDOWN".equals(serverMessage)) {
                            System.out.println("\n[System] Server has shut down. Press Enter to quit.");
                            running = false;
                            break;
                        } else if (serverMessage.startsWith("FILE_OFFER|")) {
                            handleIncomingFileOffer(serverMessage);
                        } else {
                            System.out.println(serverMessage);
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        System.out.println("\n[System] Connection to server lost.");
                    }
                }
            }).start();

            // Main input loop
            while (running && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();

                if (!running) break;

                // Handle Exit shortcuts (/q, /exit, q, exit)
                if ("/q".equalsIgnoreCase(line) || "/exit".equalsIgnoreCase(line) || "q".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                    out.println("/exit");
                    running = false;
                    System.out.println("[System] Disconnecting from chat...");
                    break;
                }

                // Handle Pending File Decision
                if (pendingOffer != null) {
                    if ("y".equalsIgnoreCase(line)) {
                        downloadFile(pendingOffer.hostIp, pendingOffer.filePort, pendingOffer.fileName);
                        pendingOffer = null;
                    } else if ("n".equalsIgnoreCase(line)) {
                        System.out.println("[System] File declined.");
                        pendingOffer = null;
                    } else {
                        System.out.println("[System] Please answer 'y' or 'n' to accept/decline the file offer.");
                    }
                    continue;
                }

                // Handle File Send shortcuts (/s <path> or /sendfile <path>)
                if (line.startsWith("/s ") || line.startsWith("/sendfile ")) {
                    int spaceIndex = line.indexOf(' ');
                    String filePath = line.substring(spaceIndex + 1).trim();
                    sendFileOffer(filePath, out, socket.getLocalAddress().getHostAddress());
                } else if (!line.isEmpty()) {
                    out.println(line);
                }
            }

        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            closeSocket();
            System.out.println("[System] Goodbye!");
            System.exit(0);
        }
    }

    private static void handleIncomingFileOffer(String offerMsg) {
        String[] parts = offerMsg.split("\\|");
        String sender = parts[1];
        String fileName = parts[2];
        long fileSize = Long.parseLong(parts[3]);
        String hostIp = parts[4];
        int filePort = Integer.parseInt(parts[5]);

        pendingOffer = new PendingFileOffer(sender, fileName, fileSize, hostIp, filePort);

        System.out.printf("\n[FILE OFFER] %s wants to send '%s' (%.2f KB). Accept? (y/n): ",
                sender, fileName, fileSize / 1024.0);
    }

    private static void sendFileOffer(String filePath, PrintWriter serverOut, String localIp) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("[System] File not found: " + filePath);
            return;
        }

        new Thread(() -> {
            try (ServerSocket fileServerSocket = new ServerSocket(0)) {
                int filePort = fileServerSocket.getLocalPort();
                String offerPayload = String.format("FILE_OFFER|%s|%s|%d|%s|%d",
                        username, file.getName(), file.length(), localIp, filePort);
                serverOut.println(offerPayload);

                System.out.println("[System] Offering file '" + file.getName() + "'. Waiting for acceptances...");

                while (running) {
                    Socket peerSocket = fileServerSocket.accept();
                    new Thread(() -> streamFileToPeer(peerSocket, file)).start();
                }
            } catch (IOException e) {
                // Socket closed on exit
            }
        }).start();
    }

    private static void streamFileToPeer(Socket peerSocket, File file) {
        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = peerSocket.getOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
            System.out.println("\n[System] File '" + file.getName() + "' sent to " + peerSocket.getInetAddress());
        } catch (IOException e) {
            System.out.println("\n[System] Error transferring file to peer.");
        } finally {
            try { peerSocket.close(); } catch (IOException ignored) {}
        }
    }

    private static void downloadFile(String hostIp, int port, String fileName) {
        File saveDir = new File("downloads");
        if (!saveDir.exists()) saveDir.mkdirs();
        File saveFile = new File(saveDir, fileName);

        new Thread(() -> {
            try (Socket downloadSocket = new Socket(hostIp, port);
                 InputStream is = downloadSocket.getInputStream();
                 FileOutputStream fos = new FileOutputStream(saveFile)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                System.out.println("\n[System] File saved to: " + saveFile.getAbsolutePath());
            } catch (IOException e) {
                System.out.println("\n[System] Failed to download file: " + e.getMessage());
            }
        }).start();
    }

    private static void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}