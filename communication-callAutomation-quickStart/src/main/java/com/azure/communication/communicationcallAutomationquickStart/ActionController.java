package com.azure.communication.communicationcallAutomationquickStart;

import com.azure.communication.callautomation.CallAutomationAsyncClient;
import com.azure.communication.callautomation.CallAutomationClientBuilder;
import com.azure.communication.callautomation.CallConnectionAsync;
import com.azure.communication.callautomation.EventHandler;
import com.azure.communication.callautomation.models.AddParticipantsOptions;
import com.azure.communication.callautomation.models.AddParticipantsResult;
import com.azure.communication.callautomation.models.AnswerCallResult;
import com.azure.communication.callautomation.models.CallMediaRecognizeDtmfOptions;
import com.azure.communication.callautomation.models.DtmfTone;
import com.azure.communication.callautomation.models.FileSource;
import com.azure.communication.callautomation.models.events.CallAutomationEventBase;
import com.azure.communication.callautomation.models.events.CallConnected;
import com.azure.communication.callautomation.models.events.RecognizeCompleted;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationEventData;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
public class ActionController {
    @Autowired
    private Environment environment;
    private CallAutomationAsyncClient client;

    private CallAutomationAsyncClient getCallAutomationAsyncClient() {
        if (client == null) {
            client = new CallAutomationClientBuilder()
                .connectionString(environment.getProperty("connectionString"))
                .endpoint("https://x-pma-euno-01.plat.skype.com")
                .buildAsyncClient();
        }
        return client;
    }

    @RequestMapping(value = "/api/incomingCall", method = POST)
    public ResponseEntity<?> handleIncomingCall(@RequestBody(required = false) String requestBody) {
        List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(requestBody);

        for (EventGridEvent eventGridEvent : eventGridEvents) {
            // Handle the subscription validation event
            if (eventGridEvent.getEventType().equals("Microsoft.EventGrid.SubscriptionValidationEvent")) {
                SubscriptionValidationEventData subscriptionValidationEventData = eventGridEvent.getData().toObject(SubscriptionValidationEventData.class);
                SubscriptionValidationResponse subscriptionValidationResponse = new SubscriptionValidationResponse()
                        .setValidationResponse(subscriptionValidationEventData.getValidationCode());
                ResponseEntity<SubscriptionValidationResponse> ret = new ResponseEntity<>(subscriptionValidationResponse, HttpStatus.OK);
                return ret;
            }

            // Answer the incoming call and pass the callbackUri where Call Automation events will be delivered
            JsonObject data = new Gson().fromJson(eventGridEvent.getData().toString(), JsonObject.class); // Extract body of the event
            String incomingCallContext = data.get("incomingCallContext").getAsString(); // Query the incoming call context info for answering
            String callerId = data.getAsJsonObject("to").get("rawId").getAsString(); // Query the id of caller for preparing the Recognize prompt.

            // Call events of this call will be sent to an url with unique id.
            String callbackUri = environment.getProperty("callbackUriBase") + String.format("/api/calls/%s?callerId=%s", UUID.randomUUID(), callerId);

            AnswerCallResult answerCallResult = getCallAutomationAsyncClient().answerCall(incomingCallContext, callbackUri).block();
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @RequestMapping(value = "/api/calls/{contextId}", method = POST)
    public ResponseEntity<?> handleCallEvents(@RequestBody String requestBody, @PathVariable String contextId, @RequestParam(name = "callerId", required = true) String callerId) {
        List<CallAutomationEventBase> acsEvents = EventHandler.parseEventList(requestBody);

        for (CallAutomationEventBase acsEvent : acsEvents) {
            if (acsEvent instanceof CallConnected) {
                CallConnected event = (CallConnected) acsEvent;

                // Call was answered and is now established
                String callConnectionId = event.getCallConnectionId();
                CommunicationIdentifier target = CommunicationIdentifier.fromRawId(callerId);

                // Play audio then recognize 3-digit DTMF input with pound (#) stop tone
                CallMediaRecognizeDtmfOptions recognizeOptions = new CallMediaRecognizeDtmfOptions(target, 3);
                recognizeOptions.setInterToneTimeout(Duration.ofSeconds(10))
                        .setStopTones(new ArrayList<>(Arrays.asList(DtmfTone.POUND)))
                        .setInitialSilenceTimeout(Duration.ofSeconds(5))
                        .setInterruptPrompt(true)
                        .setPlayPrompt(new FileSource().setUri(environment.getProperty("mediaSource")))
                        .setOperationContext("MainMenu");

                getCallAutomationAsyncClient().getCallConnectionAsync(callConnectionId)
                        .getCallMediaAsync()
                        .startRecognizing(recognizeOptions)
                        .block();
            } else if (acsEvent instanceof RecognizeCompleted) {
                RecognizeCompleted event = (RecognizeCompleted) acsEvent;

                // This RecognizeCompleted correlates to the previous action as per the OperationContext value
                if (event.getOperationContext().equals("MainMenu")) {
                    CallConnectionAsync callConnectionAsync = getCallAutomationAsyncClient().getCallConnectionAsync(event.getCallConnectionId());

                    // Invite other participants to the call
                    List<CommunicationIdentifier> participants = new ArrayList<>(
                            Arrays.asList(new CommunicationUserIdentifier(environment.getProperty("participantToAdd"))));
                    AddParticipantsOptions options = new AddParticipantsOptions(participants);
                    AddParticipantsResult addParticipantsResult = callConnectionAsync.addParticipants(participants).block();
                }
            }
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }
}