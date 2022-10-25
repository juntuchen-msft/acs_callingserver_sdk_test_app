package com.communication.quickstart;

import com.azure.communication.callautomation.CallAutomationAsyncClient;
import com.azure.communication.callautomation.CallAutomationClientBuilder;
import com.azure.communication.callautomation.CallConnectionAsync;
import com.azure.communication.callautomation.EventHandler;
import com.azure.communication.callautomation.models.AddParticipantsOptions;
import com.azure.communication.callautomation.models.AddParticipantsResult;
import com.azure.communication.callautomation.models.AnswerCallResult;
import com.azure.communication.callautomation.models.CallConnectionState;
import com.azure.communication.callautomation.models.CallMediaRecognizeDtmfOptions;
import com.azure.communication.callautomation.models.DtmfTone;
import com.azure.communication.callautomation.models.FileSource;
import com.azure.communication.callautomation.models.events.CallAutomationEventBase;
import com.azure.communication.callautomation.models.events.CallConnectedEvent;
import com.azure.communication.callautomation.models.events.RecognizeCompletedEvent;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationEventData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static spark.Spark.*;

/**
 * Quick-start app demo for Azure Communication Call Automation Java SDK
 *
 */
public class App 
{
    public static void main( String[] args )
    {
        String connectionString = "<ACS_CONNECTION_STRING>";
        String callbackUriBase = "<YOUR_NGROK_FQDN>"; // i.e. https://someguid.ngrok.io

        CallAutomationAsyncClient client = new CallAutomationClientBuilder()
                .connectionString(connectionString)
                .buildAsyncClient();

        post("/api/incomingCall", (request, response) -> {
            List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(request.body());
            for (EventGridEvent eventGridEvent : eventGridEvents) {
                // Handle the subscription validation event
                if (eventGridEvent.getEventType().equals("Microsoft.EventGrid.SubscriptionValidationEvent")) {
                    SubscriptionValidationEventData subscriptionValidationEventData = eventGridEvent.getData().toObject(SubscriptionValidationEventData.class);
                    response.status(200);
                    return "{\"validationResponse\": \"" + subscriptionValidationEventData.getValidationCode() + "\"}";
                }

                // Answer the incoming call and pass the callbackUri where Call Automation events will be delivered
                JsonObject data = new Gson().fromJson(eventGridEvent.getData().toString(), JsonObject.class);
                String incomingCallContext = data.get("incomingCallContext").getAsString();
                String callbackUri = callbackUriBase + String.format("/api/calls/%s", UUID.randomUUID());
                AnswerCallResult answerCallResult = client.answerCall(incomingCallContext, callbackUri).block();
            }
            response.status(200);
            return "";
        });

        post("/api/calls/:contextId", (request, response) -> {
            String contextId = request.params(":contextId");
            List<CallAutomationEventBase> acsEvents = EventHandler.parseEventList(request.body());

            for (CallAutomationEventBase acsEvent : acsEvents) {
                if (acsEvent instanceof CallConnectedEvent) {
                    CallConnectedEvent event = (CallConnectedEvent) acsEvent;

                    // Call was answered and is now established
                    String callConnectionId = event.getCallConnectionId();
                    if (client.getCallConnectionAsync(callConnectionId).getCallProperties().block().getCallConnectionState() == CallConnectionState.CONNECTED) {
                        PhoneNumberIdentifier target = new PhoneNumberIdentifier("<THE_NUMBER_THAT_DIALED_IN>");

                        // Play audio then recognize 3-digit DTMF input with pound (#) stop tone
                        CallMediaRecognizeDtmfOptions recognizeOptions = new CallMediaRecognizeDtmfOptions(target, 3);
                        recognizeOptions.setInterToneTimeout(Duration.ofSeconds(10))
                                .setStopTones(new ArrayList<>(Arrays.asList(DtmfTone.POUND)))
                                .setInitialSilenceTimeout(Duration.ofSeconds(5))
                                .setInterruptPrompt(true)
                                .setPlayPrompt(new FileSource().setUri("<MEDIA_URI>"))
                                .setOperationContext("MainMenu");

                        client.getCallConnectionAsync(callConnectionId)
                                .getCallMediaAsync()
                                .startRecognizing(recognizeOptions)
                                .block();
                    }
                } else if (acsEvent instanceof RecognizeCompletedEvent) {
                    RecognizeCompletedEvent event = (RecognizeCompletedEvent) acsEvent;

                    // This RecognizeCompleted correlates to the previous action as per the OperationContext value
                    if (event.getOperationContext().equals("MainMenu")) {
                        CallConnectionAsync callConnectionAsync = client.getCallConnectionAsync(event.getCallConnectionId());

                        // Invite other participants to the call
                        List<CommunicationIdentifier> participants = new ArrayList<>(
                                Arrays.asList(new CommunicationUserIdentifier("<ANOTHER_ACS_USER>")));
                        AddParticipantsOptions options = new AddParticipantsOptions(participants);
                        AddParticipantsResult addParticipantsResult = callConnectionAsync.addParticipants(participants).block();
                    }
                }
            }
            response.status(200);
            return "";
        });
    }
}
