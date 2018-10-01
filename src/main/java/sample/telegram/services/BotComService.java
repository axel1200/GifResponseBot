package sample.telegram.services;

import com.sun.deploy.Environment;
import com.sun.xml.internal.bind.marshaller.Messages;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import sample.telegram.dtos.TelegramMessageResponse;
import sample.telegram.dtos.TelegramUpdate;
import sample.telegram.dtos.TelegramUpdateResponse;
import sample.telegram.messages.SendAnimation;
import sample.telegram.messages.SendMessage;
import sample.telegram.messages.TelegramAnswer;

import java.io.UnsupportedEncodingException;
import java.net.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Log4j
public class BotComService {
    private final RestTemplate restTemplate;
    private ScheduledExecutorService scheduledExecutorService;
    private static final long FETCH_PERIOD= 2500;
    private static final String BOT_KEY=System.getenv("TELEGRAM_BOT_KEY");
    private Long lastUpdateId;

    public BotComService() {
        restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(Collections.singletonList(new GsonHttpMessageConverter()));
    }

    public void requestUpdates(UpdateProcessor updateProcessor) {
        log.info("STARTING");
        scheduledExecutorService= Executors.newSingleThreadScheduledExecutor();

        scheduledExecutorService.execute(() -> getUpdatesAndHandOver(updateProcessor));
    }

    private void getUpdatesAndHandOver(UpdateProcessor updateProcessor) {
        try{
            log.trace("Running telegram update");
            HashMap<String, String> parameters = new HashMap<>();
            if(lastUpdateId!=null){
                parameters.put("offset", String.valueOf((lastUpdateId+1L)));
            }
            parameters.put("timeout","100");

            TelegramUpdateResponse updates = restTemplate.getForObject(getURIwitthParameters("getUpdates",parameters), TelegramUpdateResponse.class);
            if((updates.getResult().isEmpty())){
                return;
            }
            lastUpdateId= Long.valueOf(updates.getResult().get(updates.getResult().size()-1).getUpdateId());

            if(!updates.isOk()){
                throw new IllegalStateException("Telegram did not hand out updates");
            }
            if(updates.getResult()!=null){
                ArrayList<TelegramUpdate> telegramUpdates = new ArrayList<>(updates.getResult());
                for (TelegramUpdate telegramUpdate:telegramUpdates){
                        log.debug(MessageFormat.format("Received new message with id: {0}",telegramUpdate.getUpdateId()));
                        updateProcessor.processUpdate(telegramUpdate);
                }
            }else {
                log.warn("No updates found");
            }

        }catch (Exception e){
            log.error("Exception during the fetching of updates",e);
        }
        if(scheduledExecutorService.isShutdown()) return;
        scheduledExecutorService.schedule(()->getUpdatesAndHandOver(updateProcessor),FETCH_PERIOD,TimeUnit.MILLISECONDS);
    }

    private String getURIwitthParameters(String method, Map<String,String> parameters) {
        java.util.Map<String, List<String>> multiValues = parameters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Collections.singletonList(String.valueOf(entry.getValue()))));
        UriComponents uriComponents= UriComponentsBuilder.fromHttpUrl("https://api.telegram.org"+MessageFormat.format("/bot{0}/{1}",BOT_KEY,method))
                .replaceQueryParams(CollectionUtils.toMultiValueMap(multiValues))
                .build();
        return uriComponents.toUriString();
    }

    public void stop() {
        log.info("Shutting down");
        scheduledExecutorService.shutdown();
        HashMap<String, String> parameters = new HashMap<>();
        parameters.put("offset","100");
        restTemplate.getForObject(getURIwitthParameters("getUpdates",parameters), TelegramUpdateResponse.class);
        lastUpdateId=null;
    }

    public void respond(List<TelegramAnswer> telegramAnswers){
        telegramAnswers.forEach(this::respondSingle);
    }

    private void respondSingle(TelegramAnswer telegramAnswer) {
        if(telegramAnswer==null){
            return;
        }
        if(telegramAnswer.getClass()==SendMessage.class){
            SendMessage message = (SendMessage) telegramAnswer;
            send(message);
            return;
        }else if(telegramAnswer.getClass()==SendAnimation.class){
            SendAnimation sendAnimation= (SendAnimation) telegramAnswer;
            send(sendAnimation);
            return;
        }
        throw new IllegalStateException("Unknown Message class "+telegramAnswer.getClass());
    }

    @SneakyThrows
    private void send(SendAnimation sendAnimation) {
        HashMap<String, String> parameters = new HashMap<>();
        parameters.put("chat_id", String.valueOf(sendAnimation.getChatId()));
        parameters.put("animation",sendAnimation.getAnimationUrl());
        TelegramMessageResponse sendMessage;

        sendMessage = restTemplate.getForObject(getURIwitthParameters("sendAnimation",parameters), TelegramMessageResponse.class);

        if(!sendMessage.isOk()){
            throw new RuntimeException("Telegram did not accept message");
        }
    }

    @SneakyThrows
    private void send(SendMessage message) {
        HashMap<String, String> parameters = new HashMap<>();
        parameters.put("chat_id", String.valueOf(message.getChatId()));
        parameters.put("text",message.getText());
        TelegramMessageResponse sendMessage;

        sendMessage = restTemplate.getForObject(getURIwitthParameters("sendMessage",parameters), TelegramMessageResponse.class);

        if(!sendMessage.isOk()){
            throw new RuntimeException("Telegram did not accept message");
        }
    }

}
