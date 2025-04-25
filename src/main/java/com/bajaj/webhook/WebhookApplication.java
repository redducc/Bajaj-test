package com.bajaj.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@SpringBootApplication
public class WebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookApplication.class, args);
    }

    @Bean
    CommandLineRunner runOnStartup() {
        return args -> {
            RestTemplate restTemplate = new RestTemplate();
            ObjectMapper objectMapper = new ObjectMapper();

            String generateUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("name", "John Doe");
            requestBody.put("regNo", "REG12347");
            requestBody.put("email", "john@example.com");

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            // Call generateWebhook endpoint
            Map<String, Object> response = restTemplate.postForObject(generateUrl, request, Map.class);
            System.out.println("API Response: " + (response != null ? response : "null"));

            if (response == null) {
                throw new IllegalStateException("Failed to fetch webhook response");
            }

            String webhookUrl = (String) response.get("webhook");
            String accessToken = (String) response.get("accessToken");
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            if (webhookUrl == null || accessToken == null || data == null) {
                throw new IllegalStateException("Invalid webhook response: missing webhook URL, access token, or data");
            }

            // Determine the question based on the last two digits of regNo
            String regNo = requestBody.get("regNo");
            int lastTwoDigits = Integer.parseInt(regNo.substring(regNo.length() - 2));
            boolean isOdd = lastTwoDigits % 2 != 0;

            Map<String, Object> result = new HashMap<>();
            result.put("regNo", regNo);

            Object usersObj = data.get("users");
            List<Map<String, Object>> users = null;
            Integer findId = null;
            Integer n = null;

            // Extract data based on response structure
            if (usersObj instanceof List) {
                users = (List<Map<String, Object>>) usersObj;
            } else if (usersObj instanceof Map) {
                Map<String, Object> usersMap = (Map<String, Object>) usersObj;
                users = (List<Map<String, Object>>) usersMap.get("users");
                findId = (Integer) usersMap.get("findId");
                n = (Integer) usersMap.get("n");
            }

            if (users == null) {
                throw new IllegalStateException("Invalid users data in response");
            }

            // Process based on regNo, forcing Question 1 if regNo is odd
            if (isOdd) {
                // Force Question 1 (Mutual Followers) regardless of response structure
                System.err.println("Forcing Question 1 processing since regNo is odd.");
                List<List<Integer>> mutualFollowPairs = findMutualFollowers(users);
                result.put("outcome", mutualFollowPairs);
            } else {
                // Expect Question 2 (Nth-Level Followers)
                if (findId != null && n != null) {
                    List<Integer> nthLevelFollowers = findNthLevelFollowers(users, findId, n);
                    result.put("outcome", nthLevelFollowers);
                } else {
                    System.err.println("Warning: Expected Question 2 data but received Question 1 data. Processing as Question 1.");
                    List<List<Integer>> mutualFollowPairs = findMutualFollowers(users);
                    result.put("outcome", mutualFollowPairs);
                }
            }

            // Prepare webhook request
            HttpHeaders webhookHeaders = new HttpHeaders();
            webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
            webhookHeaders.set("Authorization", accessToken);

            HttpEntity<Map<String, Object>> webhookRequest = new HttpEntity<>(result, webhookHeaders);

            // Retry logic with exponential backoff: up to 4 attempts
            int maxRetries = 4;
            int attempt = 0;
            boolean success = false;

            while (attempt < maxRetries && !success) {
                try {
                    System.out.println("Webhook attempt " + attempt + ": Sending to " + webhookUrl + " with payload: " + result);
                    System.out.println("Webhook headers: " + webhookHeaders);
                    ResponseEntity<String> responseEntity = restTemplate.exchange(webhookUrl, HttpMethod.POST, webhookRequest, String.class);
                    System.out.println("Webhook response: " + responseEntity.getStatusCode() + " - " + responseEntity.getBody());
                    success = true;
                } catch (RestClientException e) {
                    System.err.println("Webhook attempt " + attempt + " failed: " + e.getMessage());
                    attempt++;
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    // Exponential backoff: 1s, 2s, 4s, 8s
                    long delay = (long) Math.pow(2, attempt) * 1000;
                    System.out.println("Waiting " + delay + "ms before retrying...");
                    Thread.sleep(delay);
                }
            }
        };
    }

    // Question 1: Find mutual followers
    private List<List<Integer>> findMutualFollowers(List<Map<String, Object>> users) {
        Map<Integer, List<Integer>> followsMap = new HashMap<>();
        for (Map<String, Object> user : users) {
            Integer id = (Integer) user.get("id");
            List<Integer> follows = (List<Integer>) user.get("follows");
            followsMap.put(id, follows);
        }

        List<List<Integer>> mutualPairs = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        for (Integer userId : followsMap.keySet()) {
            List<Integer> follows = followsMap.get(userId);
            for (Integer followedId : follows) {
                if (followsMap.containsKey(followedId) && followsMap.get(followedId).contains(userId)) {
                    int min = Math.min(userId, followedId);
                    int max = Math.max(userId, followedId);
                    String pairKey = min + "-" + max;
                    if (!seenPairs.contains(pairKey)) {
                        mutualPairs.add(Arrays.asList(min, max));
                        seenPairs.add(pairKey);
                    }
                }
            }
        }

        mutualPairs.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        return mutualPairs;
    }

    // Question 2: Find nth-level followers using BFS
    private List<Integer> findNthLevelFollowers(List<Map<String, Object>> users, int findId, int n) {
        Map<Integer, List<Integer>> graph = new HashMap<>();
        for (Map<String, Object> user : users) {
            Integer id = (Integer) user.get("id");
            List<Integer> follows = (List<Integer>) user.get("follows");
            graph.put(id, follows);
        }

        Map<Integer, Integer> levels = new HashMap<>();
        Queue<Integer> queue = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();

        queue.offer(findId);
        levels.put(findId, 0);
        visited.add(findId);

        while (!queue.isEmpty()) {
            int current = queue.poll();
            int currentLevel = levels.get(current);

            List<Integer> followers = graph.getOrDefault(current, Collections.emptyList());
            for (Integer follower : followers) {
                if (!visited.contains(follower)) {
                    queue.offer(follower);
                    levels.put(follower, currentLevel + 1);
                    visited.add(follower);
                }
            }
        }

        List<Integer> nthLevelFollowers = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : levels.entrySet()) {
            if (entry.getValue() == n) {
                nthLevelFollowers.add(entry.getKey());
            }
        }

        Collections.sort(nthLevelFollowers);
        return nthLevelFollowers;
    }
}
