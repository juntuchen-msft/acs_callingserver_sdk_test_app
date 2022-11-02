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
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationEventData;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
public class ActionController {
    @Autowired
    private Environment environment;

    @RequestMapping(value = "/api/incomingCall", method = POST)
    public ResponseEntity<?> handleIncomingCall(@RequestBody(required = false) String requestBody) {
        CallAutomationAsyncClient client = new CallAutomationClientBuilder()
                .connectionString(environment.getProperty("connectionString"))
                .endpoint("https://pma-dev-fanche.plat-dev.skype.net")
                .buildAsyncClient();
        List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(requestBody);
        System.out.println(requestBody);

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
            String incomingCallContext = eventGridEvent.getData().toString().split("\"incomingCallContext\":\"")[1].split("\"}")[0];
            String callerId = eventGridEvent.getSubject().split("caller/")[1].split("/recipient/")[0];
            String callbackUri = environment.getProperty("callbackUriBase") + String.format("/api/calls/%s", callerId);

            // Only answer incoming call that is to the call server
            if (Objects.equals(eventGridEvent.getSubject().split("recipient/")[1], environment.getProperty("serverPhoneNum"))) {
                AnswerCallResult answerCallResult = client.answerCall(incomingCallContext, callbackUri).block();
            }
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @RequestMapping(value = "/api/calls/{callerId}", method = POST)
    public ResponseEntity<?> handleCallEvents(@RequestBody(required = false) String requestBody, @PathVariable String callerId) {
        CallAutomationAsyncClient client = new CallAutomationClientBuilder()
                .connectionString(environment.getProperty("connectionString"))
                .endpoint("https://pma-dev-fanche.plat-dev.skype.net")
                .buildAsyncClient();
        List<CallAutomationEventBase> acsEvents = EventHandler.parseEventList(requestBody);
        System.out.println(requestBody);

        for (CallAutomationEventBase acsEvent : acsEvents) {
            if (acsEvent instanceof CallConnected) {
                CallConnected event = (CallConnected) acsEvent;

                // Call was answered and is now established
                String callConnectionId = event.getCallConnectionId();
//                PhoneNumberIdentifier target = new PhoneNumberIdentifier(callerId);
                CommunicationUserIdentifier target = new CommunicationUserIdentifier("8:acs:816df1ca-971b-44d7-b8b1-8fba90748500_00000014-da5e-6b21-2207-933a0d000025");
                System.out.println(event.getCorrelationId());

                // Play audio then recognize 3-digit DTMF input with pound (#) stop tone
                CallMediaRecognizeDtmfOptions recognizeOptions = new CallMediaRecognizeDtmfOptions(target, 3);
                recognizeOptions.setInterToneTimeout(Duration.ofSeconds(10))
                        .setStopTones(new ArrayList<>(Arrays.asList(DtmfTone.POUND)))
                        .setInitialSilenceTimeout(Duration.ofSeconds(5))
                        .setInterruptPrompt(true)
                        .setPlayPrompt(new FileSource().setUri(environment.getProperty("mediaSource")))
                        .setOperationContext("MainMenu");

                client.getCallConnectionAsync(callConnectionId)
                        .getCallMediaAsync()
                        .startRecognizing(recognizeOptions)
                        .block();
            } else if (acsEvent instanceof RecognizeCompleted) {
                RecognizeCompleted event = (RecognizeCompleted) acsEvent;

                // This RecognizeCompleted correlates to the previous action as per the OperationContext value
                if (event.getOperationContext().equals("MainMenu")) {
                    CallConnectionAsync callConnectionAsync = client.getCallConnectionAsync(event.getCallConnectionId());

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
