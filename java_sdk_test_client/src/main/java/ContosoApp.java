import com.azure.communication.callingserver.CallConnection;
import com.azure.communication.callingserver.CallingServerClient;
import com.azure.communication.callingserver.CallingServerClientBuilder;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.communication.identity.CommunicationIdentityClient;
import com.azure.communication.identity.CommunicationIdentityClientBuilder;

import java.util.ArrayList;
import java.util.List;

public class ContosoApp {

    public static void main(String[] args) {
        String acsConnectionString = "";
        String devPMAEndPoint = "";
        String callbackUri = "";
        CommunicationUserIdentifier target = new CommunicationUserIdentifier("");

        // Java SDK Initialization
        CallingServerClient callingServerClient = new CallingServerClientBuilder()
                .connectionString(acsConnectionString)
                .endpoint(devPMAEndPoint)
                .buildClient(true);

        // Initialize the caller
        CommunicationIdentityClient communicationClient = new CommunicationIdentityClientBuilder()
                .connectionString(acsConnectionString)
                .buildClient();
        CommunicationUserIdentifier source = communicationClient.createUser();
        List<CommunicationIdentifier> targets = new ArrayList<CommunicationIdentifier>();
        targets.add(target);

        CallConnection result = callingServerClient.createCall(source, targets, callbackUri, null, null);
        System.out.print(result.getCallConnectionId());
    }
}
