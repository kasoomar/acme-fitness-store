package com.example.acme.assist;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatRole;
import com.example.acme.assist.model.AcmeChatRequest;
import com.example.acme.assist.model.Product;
import com.example.acme.assist.vectorstore.CosmosDBVectorStore;
import com.example.acme.assist.vectorstore.DocEntry;
import io.micrometer.common.util.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.client.AiClient;
import org.springframework.ai.client.AiResponse;
import org.springframework.ai.client.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.prompt.Prompt;
import org.springframework.ai.prompt.SystemPromptTemplate;
import org.springframework.ai.prompt.messages.ChatMessage;
import org.springframework.ai.prompt.messages.Message;
import org.springframework.ai.vectorstore.impl.SimplePersistentVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    @Autowired
    private SimplePersistentVectorStore store;

    @Autowired
    private AzureOpenAIClient openAIClient;

    @Autowired
    private CosmosDBVectorStore cosmosDBVectorStore;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AiClient aiClient;

    @Value("classpath:/prompts/chatWithoutProductId.st")
    private Resource chatWithoutProductIdResource;

    @Value("classpath:/prompts/chatWithProductId.st")
    private Resource chatWithProductIdResource;

    @Value("${spring.data.mongodb.enabled}")
    private String cosmosEnabled;
    /**
     * Chat with the OpenAI API. Use the product details as the context.
     *
     * @param chatRequestMessages the chat messages
     * @return the chat response
     */
    public List<String> chat(List<AcmeChatRequest.Message> chatRequestMessages, String productId) {

        validateMessage(chatRequestMessages);

        // step 1. Retrieve the product if available
        Product product = productRepository.getProductById(productId);
        // If no specific product is found, search the vector store to find something that matches the request.
        if (product == null) {
            return chatWithoutProductId(chatRequestMessages);
        } else {
            return chatWithProductId(product, chatRequestMessages);
        }
    }

    private List<String> chatWithProductId(Product product, List<AcmeChatRequest.Message> chatRequestMessages) {
        // We have a specific Product
        String question = chatRequestMessages.get(chatRequestMessages.size() - 1).getContent();

        var response = openAIClient.getEmbeddings(List.of(question));
        var embedding = response.getData().get(0).getEmbedding();


        List<Document> candidateDocuments = new ArrayList<>();;
        // step 1. Query for documents that are related to the question from the vector store
        if (cosmosEnabled.equals("true")) {
            List<DocEntry> cosmosVectorStoreDocs = this.cosmosDBVectorStore.searchTopKNearest(embedding, 5, 0.4);
            for (DocEntry docEntry : cosmosVectorStoreDocs) {
                Document document = new Document(docEntry.getText());
                candidateDocuments.add(document);
            }
        }
        else
        {
            candidateDocuments = this.store.similaritySearch(question, 5, 0.4);
        }

        // step 2. Create a SystemMessage that contains the product information in addition to related documents.
        List<Message> messages = new ArrayList<>();
        Message productDetailMessage =  getProductDetailMessage(product, candidateDocuments);
        messages.add(productDetailMessage);

        // step 3. Send the system message and the user's chat request messages to OpenAI
        return addUserMessagesAndSendToAI(chatRequestMessages, messages);
    }

    /**
     * Chat with the OpenAI API. Search the vector store for the top 5 related documents
     * to the questions and use them as the system context.
     *
     * @param acmeChatRequestMessages the chat messages, including previous messages sent by the client
     * @return the chat response
     */
    protected List<String> chatWithoutProductId(List<AcmeChatRequest.Message> acmeChatRequestMessages) {

        String question = acmeChatRequestMessages.get(acmeChatRequestMessages.size() - 1).getContent();

        var response = openAIClient.getEmbeddings(List.of(question));
        var embedding = response.getData().get(0).getEmbedding();

        // step 1. Query for documents that are related to the question from the vector store
        List<Document> relatedDocuments = new ArrayList<>();;
        if (cosmosEnabled.equals("true")) {
            List<DocEntry> cosmosVectorStoreDocs = this.cosmosDBVectorStore.searchTopKNearest(embedding, 5, 0.4);
            for (DocEntry docEntry : cosmosVectorStoreDocs) {
                Document document = new Document(docEntry.getText());
                relatedDocuments.add(document);
            }
        }
        else {
            relatedDocuments = this.store.similaritySearch(question, 5, 0.4);
        }


        // step 2. Create the system message with the related documents;
        List<Message> messages = new ArrayList<>();
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(this.chatWithoutProductIdResource);
        String relatedDocsAsString = relatedDocuments.stream()
                .map(entry -> String.format("Product Name: %s\nText: %s\n", entry.getMetadata().get("name"), entry.getText()))
                .collect(Collectors.joining("\n"));
        Message systemMessage = systemPromptTemplate.createMessage(Map.of("context", relatedDocsAsString));
        messages.add(systemMessage);

        // step 3. Send the system message and the user's chat request messages to OpenAI
        return addUserMessagesAndSendToAI(acmeChatRequestMessages, messages);
    }

    private List<String> addUserMessagesAndSendToAI(List<AcmeChatRequest.Message> acmeChatRequestMessages, List<Message> messages) {
        // Convert to acme messages types to Spring AI message types
        for (AcmeChatRequest.Message acmeChatRequestMessage : acmeChatRequestMessages) {
            String role = acmeChatRequestMessage.getRole().toString().toUpperCase();
            messages.add(new ChatMessage(role, acmeChatRequestMessage.getContent()));
        }

        // Call to OpenAI chat API
        Prompt prompt = new Prompt(messages);
        AiResponse aiResponse = this.aiClient.generate(prompt);

        // Process the result and return to client
        List<String> response = processResult(aiResponse);

        return response;
    }

    public Message getProductDetailMessage(Product product, List<Document> documents) {
        String additionalContext = documents.stream()
                .map(entry -> String.format("Product Name: %s\nText: %s\n", entry.getMetadata().get("name"), entry.getText()))
                .collect(Collectors.joining("\n"));
        Map map = Map.of(
                "name", product.getName(),
                "tags", String.join(",", product.getTags()),
                "shortDescription", product.getShortDescription(),
                "fullDescription", product.getDescription(),
                "additionalContext", additionalContext);
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(this.chatWithProductIdResource);
        return systemPromptTemplate.create(map).getMessages().get(0);
    }

    private List<String> processResult(AiResponse aiResponse) {
        List<String> response = aiResponse.getGenerations().stream()
                .map(Generation::getText)
                .filter(text -> !StringUtils.isEmpty(text))
                .map(this::filterMessage)
                .collect(Collectors.toList());
        return response;
    }

    private static void validateMessage(List<AcmeChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("message shouldn't be empty.");
        }

        if (messages.get(0).getRole() != ChatRole.USER) {
            throw new IllegalArgumentException("The first message should be in user role.");
        }

        var lastUserMessage = messages.get(messages.size() - 1);
        if (lastUserMessage.getRole() != ChatRole.USER) {
            throw new IllegalArgumentException("The last message should be in user role.");
        }
    }

    private String filterMessage(String content) {
        if (Strings.isEmpty(content)) {
            return "";
        }
        List<Product> products = productRepository.getProductList();
        for (Product product : products) {
            content = content.replace(product.getName(), "{{" + product.getName() + "|" + product.getId() + "}}");
        }
        return content;
    }
}
