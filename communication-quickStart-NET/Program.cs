using Azure.Communication;
using Azure.Communication.CallAutomation;
using Azure.Messaging;
using Azure.Messaging.EventGrid;
using Azure.Messaging.EventGrid.SystemEvents;
using Microsoft.AspNetCore.Mvc;
using System.ComponentModel.DataAnnotations;
using System.Text.Json.Nodes;

var builder = WebApplication.CreateBuilder(args);

var client = new CallAutomationClient(builder.Configuration["ConnectionString"]);
var callbackUriBase = builder.Configuration["callbackUriBase"]; // i.e. https://someguid.ngrok.io

var app = builder.Build();
app.MapPost("/api/incomingCall", async (
    [FromBody] EventGridEvent[] eventGridEvents) =>
{
    foreach (var eventGridEvent in eventGridEvents)
    {
        // Handle system events
        if (eventGridEvent.TryGetSystemEventData(out object eventData))
        {
            // Handle the subscription validation event
            if (eventData is SubscriptionValidationEventData subscriptionValidationEventData)
            {
                var responseData = new SubscriptionValidationResponse
                {
                    ValidationResponse = subscriptionValidationEventData.ValidationCode
                };
                return Results.Ok(responseData);
            }
        }
        var jsonObject = JsonNode.Parse(eventGridEvent.Data).AsObject();
        var callerId = (string)(jsonObject["to"]["rawId"]);
        var incomingCallContext = (string)jsonObject["incomingCallContext"];
        var callbackUri = new Uri(callbackUriBase + $"/api/calls/{Guid.NewGuid()}?callerId={callerId}");

        AnswerCallResult answerCallResult = await client.AnswerCallAsync(incomingCallContext, callbackUri);
    }
    return Results.Ok();
});

app.MapPost("/api/calls/{contextId}", async (
    [FromBody] CloudEvent[] cloudEvents,
    [FromRoute] string contextId,
    [Required] string callerId) =>
{
    foreach (var cloudEvent in cloudEvents)
    {
        CallAutomationEventBase @event = CallAutomationEventParser.Parse(cloudEvent);
        if (@event is CallConnected)
        {
            // play audio then recognize 3-digit DTMF input with pound (#) stop tone
            var recognizeOptions =
                new CallMediaRecognizeDtmfOptions(CommunicationIdentifier.FromRawId(callerId), 3)
                {
                    InterruptPrompt = true,
                    InterToneTimeout = TimeSpan.FromSeconds(10),
                    InitialSilenceTimeout = TimeSpan.FromSeconds(5),
                    Prompt = new FileSource(new Uri(builder.Configuration["MediaSource"])),
                    StopTones = new[] { DtmfTone.Pound },
                    OperationContext = "MainMenu"
                };
            await client.GetCallConnection(@event.CallConnectionId)
                .GetCallMedia()
                .StartRecognizingAsync(recognizeOptions);
        }
        if (@event is RecognizeCompleted { OperationContext: "MainMenu" })
        {
            // this RecognizeCompleted correlates to the previous action as per the OperationContext value
            await client.GetCallConnection(@event.CallConnectionId)
                .AddParticipantsAsync(new AddParticipantsOptions(
                    new List<CommunicationIdentifier>()
                    {
                        new CommunicationUserIdentifier(builder.Configuration["ParticipantToAdd"])
                    })
                );
        }
    }
    return Results.Ok();
}).Produces(StatusCodes.Status200OK);

app.Run();