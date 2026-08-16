# 調査記録：`@Async` がself-invocationで効かない理由

## 症状

`OrderService.confirm()` は注文確定結果を正しく返す。しかし、通知処理へ `@Async` を付けたにもかかわらず、同じBean内の呼び出しでは通知が呼び出しスレッドで同期実行される。

## 不具合状態の観測

不具合状態の `mvn test` 結果を [`../evidence/01-broken-test-output.txt`](../evidence/01-broken-test-output.txt) に保存した。

```text
Tests run: 2, Failures: 1, Errors: 0
通知はExecutorスレッドで実行されるべき ==> expected: <true> but was: <false>
```

失敗したテストでは、`OrderService.confirm()` から同じクラスの `sendNotification()` を直接呼んでいる。戻り値 `confirmed:order-1` は正しいため、実行スレッドを観測値として追加した。

| 観測項目 | 期待 | 不具合状態 |
|---|---|---|
| 注文確定結果 | `confirmed:order-1` | `confirmed:order-1` |
| `confirm()`内の通知実行スレッド | `notification-1` | JUnitの呼び出しスレッド（通常`main`） |
| 外部からproxy経由で通知を呼ぶ場合 | `notification-1` | `notification-1` |

## 競合仮説の比較

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| `@EnableAsync`が無効で、すべての`@Async`が同期実行される | 外部からの通知も呼び出しスレッドになる | `NotificationService`をBeanとして外部から呼ぶ | `notification-1`になる | 棄却 |
| Executorの設定が誤っている | proxy経由でも名前付きExecutorへ移らない | 固定名`notification-`のExecutorを観測 | proxy経由では`notification-1` | 棄却 |
| 同じBean内のself-invocationがproxyを迂回する | 内部呼び出しだけ呼び出しスレッドになる | 同一Bean呼び出しと別Bean呼び出しを比較 | 内部だけ同期実行 | 採用 |

## 根本原因

Spring AOPはproxy-basedである。クライアントがproxyを参照してメソッドを呼ぶと、proxyがinterceptorを通じて対象メソッドへ委譲する。一方、対象オブジェクトの内部で `this.sendNotification()`、または暗黙の `sendNotification()` を呼ぶと、呼び出し先はproxyではなく対象オブジェクト自身になる。そのため、`@Async`に対応するadviceが実行されない。[1]

`@Async`はメソッドを非同期実行の候補としてマークするアノテーションであり、戻り値は`void`または`Future`系に制約される。[2] アノテーションの有無だけで、同一オブジェクト内の直接呼び出しが自動的にExecutorへ移るわけではない。

## 最小修正

修正前は、`OrderService`自身が`@Async`メソッドを持っていた。

```java
@Service
class OrderService {
    public String confirm(String orderId) {
        sendNotification(orderId); // this相当のself-invocation
        return "confirmed:" + orderId;
    }

    @Async("notificationExecutor")
    public void sendNotification(String orderId) {
        // 通知処理
    }
}
```

修正後は`NotificationService`を別Beanに分離し、`OrderService`からそのBeanを注入して呼ぶ。

```java
@Service
class OrderService {
    private final NotificationService notificationService;

    public String confirm(String orderId) {
        notificationService.sendNotification(orderId);
        return "confirmed:" + orderId;
    }
}

@Service
class NotificationService {
    @Async("notificationExecutor")
    public void sendNotification(String orderId) {
        // 通知処理
    }
}
```

この呼び出しは別Beanの参照を通るため、Springが管理するproxyが`@Async` adviceを適用できる。Springは`TaskExecutor`をタスク実行の抽象化として提供しており、本ラボでは名前付きの単一スレッドExecutorを固定して観測を決定的にした。[3]

`AopContext.currentProxy()`を使う方法もあるが、Spring AOPへの結合が強くなる。公式リファレンスも、まずself-invocationを避けるリファクタリングを示している。[1] 本ラボでは、最小かつ設計上説明しやすい別Beanへの分離を採用した。

## 回帰確認

修正後の `mvn clean test` は次の結果になった。

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

元の失敗テスト `confirm_shouldDispatchNotificationToExecutorThread` は残し、別Beanを直接proxy経由で呼ぶ対照ケースも残している。完全な成功出力は [`../evidence/02-fixed-test-output.txt`](../evidence/02-fixed-test-output.txt) に保存した。

## 参考資料

[1]: https://docs.spring.io/spring-framework/reference/core/aop/proxying.html "Proxying Mechanisms — Spring Framework Reference"
[2]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html "Async — Spring Framework Javadoc"
[3]: https://docs.spring.io/spring-framework/reference/integration/scheduling.html "Task Execution and Scheduling — Spring Framework Reference"
