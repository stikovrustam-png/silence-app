package app.silence.messenger;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Принимает push от сервера. В push нет текста сообщений — это только сигнал «загляни на сервер». */
public class SilenceFcmService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        FcmHelper.register(getApplicationContext());
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        NotifyService.once(getApplicationContext());
    }
}
