package jp.tonbiattack.debuglab;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final NotificationService notificationService;

    public OrderService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public String confirm(String orderId) {
        notificationService.sendNotification(orderId);
        return "confirmed:" + orderId;
    }
}
