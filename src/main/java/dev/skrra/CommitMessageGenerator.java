package dev.skrra;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import org.json.JSONObject;

public class CommitMessageGenerator {

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String MODEL = "gpt-4o-mini"; // Replace with the correct model name if needed

    public static void main(String[] args) {
        try {
            // Step 1: Check for staged changes
            if (!hasStagedChanges()) {
                System.out.println("No staged changes to generate a commit message.");
                System.exit(0);
            }

            // Step 2: Get staged files and diffs
            String stagedFiles = getStagedFiles();
            String stagedDiff = getStagedDiff();

            // Step 3: Generate commit message using OpenAI API
            String commitMessage = generateCommitMessage(stagedDiff);

            if (commitMessage == null || commitMessage.isEmpty()) {
                System.out.println("Failed to generate commit message.");
                System.exit(1);
            }

            // Step 4: Display the commit message and staged files
            System.out.println("------------------------------");
            System.out.println("Proposed Commit Message:");
            System.out.println(commitMessage);
            System.out.println("------------------------------");
            System.out.println("Staged Files:");
            System.out.println(stagedFiles);
            System.out.println("------------------------------");

            copyToClipboard(commitMessage);

        } catch (Exception e) {
            System.err.println("An error occurred: " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean hasStagedChanges() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached", "--quiet");
        Process process = pb.start();
        int exitCode = process.waitFor();
        return exitCode != 0;
    }

    private static String getStagedFiles() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached", "--name-only");
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder files = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            files.append(line).append("\n");
        }
        process.waitFor();
        return files.toString().trim();
    }

    private static String getStagedDiff() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached");
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder diff = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            diff.append(line).append("\n");
        }
        process.waitFor();
        return diff.toString().trim();
    }

    private static String generateCommitMessage(String diff) throws IOException, InterruptedException {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            System.err.println("OPENAI_API_KEY environment variable is not set.");
            return null;
        }

        HttpClient client = HttpClient.newHttpClient();
        JSONObject json = new JSONObject();
        json.put("model", MODEL);

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "You are a helpful assistant for generating git commit messages.");

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", "Generate a concise and meaningful git commit message based on the following staged changes:\n\n" + diff);

        json.put("messages", new org.json.JSONArray().put(systemMessage).put(userMessage));
        json.put("max_tokens", 100);
        json.put("n", 1);
        json.put("stop", JSONObject.NULL);
        json.put("temperature", 0.5);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("OpenAI API request failed with status code: " + response.statusCode());
            System.err.println("Response: " + response.body());
            return null;
        }

        JSONObject responseJson = new JSONObject(response.body());
        String commitMessage = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();

        return commitMessage;
    }

    private static void copyToClipboard(String message) throws IOException, InterruptedException {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("mac")) {
            pb = new ProcessBuilder("pbcopy");
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            pb = new ProcessBuilder("xclip", "-selection", "clipboard");
        } else {
            System.out.println("Clipboard utility not supported on this OS.");
            System.out.println("Commit Message:");
            System.out.println(message);
            return;
        }

        Process process = pb.start();
        process.getOutputStream().write(message.getBytes());
        process.getOutputStream().close();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.out.println("Commit message copied to clipboard.");
        } else {
            System.out.println("Failed to copy commit message to clipboard.");
            System.out.println("Commit Message:");
            System.out.println(message);
        }
    }

    private static String editCommitMessage(String message) throws IOException {
        // Create a temporary file
        String tmpFilePath = System.getProperty("java.io.tmpdir") + "commit_message.txt";
        java.nio.file.Files.writeString(java.nio.file.Paths.get(tmpFilePath), message);

        // Open the default editor
        String editor = System.getenv("EDITOR");
        if (editor == null || editor.isEmpty()) {
            editor = "vi"; // Default to vi if EDITOR is not set
        }

        ProcessBuilder pb = new ProcessBuilder(editor, tmpFilePath);
        pb.inheritIO();
        try {
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Failed to open editor: " + e.getMessage());
            return null;
        }

        // Read the edited message
        String editedMessage = java.nio.file.Files.readString(java.nio.file.Paths.get(tmpFilePath)).trim();
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tmpFilePath));

        return editedMessage;
    }
}
