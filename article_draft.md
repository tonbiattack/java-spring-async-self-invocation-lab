# Springの`@Async`が同じBean内の呼び出しで効かない理由：self-invocationとproxy境界を最小再現から理解する

`@Async`を付けたメソッドは別スレッドで実行される。Springを使っていると、そう期待してコードを書くことがあります。しかし、次のような同じBean内の呼び出しでは、その期待が崩れます。

```java
@Service
class OrderService {
    public String confirm(String orderId) {
        sendNotification(orderId);
        return "confirmed:" + orderId;
    }

    @Async("notificationExecutor")
    public void sendNotification(String orderId) {
        // 通知処理
    }
}
```

`confirm()`の戻り値は正しくても、通知処理は呼び出しスレッドで同期実行されます。本稿では、Spring Boot 3.4.4、Spring Framework 6.2.x、Java 21を固定し、実行スレッド名を観測値としてこの挙動を再現します。結論は、**`@Async`の効果はアノテーションだけで決まらず、Spring AOP proxyを経由した呼び出しであることが必要**ということです。

## この記事で扱う問題

注文確定処理は通知を開始したあと、注文確定結果を返します。通知は呼び出し元のリクエストスレッドを待たせず、名前付きExecutorの別スレッドで実行される契約とします。

| 入力・操作 | 期待する結果 | 実際の不具合状態 |
|---|---|---|
| `OrderService.confirm("order-1")` | `confirmed:order-1`を返し、通知は`notification-1`で実行 | 戻り値は正しいが、通知は呼び出しスレッドで実行 |
| `NotificationService.sendNotification("order-2")`をBean外部から呼ぶ | `notification-1`で実行 | `notification-1`で実行 |

`@Async`は非同期実行の候補としてメソッドをマークします。公式Javadocでは、戻り値は`void`または`Future`系に制約されると説明されています。[2] ただし、同じクラスの内部呼び出しをproxy経由へ変換するアノテーションではありません。

## 既存題材との差分

既存のqiita記事とJavaラボには、`@Transactional`の検査例外によるロールバック漏れ、トランザクション境界、JPA、Spring Security、Cache、JSON契約などの題材があります。これらはDBの更新境界、例外規則、認可、シリアライズの挙動を扱います。

今回の題材はそれらと異なり、**同じBean内のself-invocationがSpring AOP adviceを迂回するため、`@Async`のスレッド切り替えが発生しない**というproxy境界を扱います。戻り値やDB状態だけではなく、実行スレッドを観測する点も固有です。

## 最小再現プロジェクト

プロジェクトは [`java-spring-async-self-invocation-lab`](https://github.com/tonbiattack/java-spring-async-self-invocation-lab) にあります。主な構成は次のとおりです。

```text
src/main/java/jp/tonbiattack/debuglab/AsyncDebugApplication.java
src/main/java/jp/tonbiattack/debuglab/AsyncConfig.java
src/main/java/jp/tonbiattack/debuglab/OrderService.java
src/main/java/jp/tonbiattack/debuglab/NotificationService.java
src/test/java/jp/tonbiattack/debuglab/OrderServiceTest.java
docs/investigation.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
```

`AsyncDebugApplication`に`@EnableAsync`を付け、`AsyncConfig`で`notification-`という接頭辞を持つ単一スレッドの`ThreadPoolTaskExecutor`を定義します。Executorを固定することで、テストの観測結果を決定的にします。Springは`TaskExecutor`をタスク実行の抽象化として提供しています。[3]

### 不具合状態を実行する

不具合状態はコミット `553a012` に保存しています。

```bash
git checkout 553a012
mvn test
```

失敗テストは、注文確定の利用者視点の契約を表します。

```java
@Test
void confirm_shouldDispatchNotificationToExecutorThread() {
    String callerThread = Thread.currentThread().getName();

    String result = orderService.confirm("order-1");

    assertEquals("confirmed:order-1", result);
    assertTrue(orderService.lastNotificationThread().startsWith("notification-"),
            "通知はExecutorスレッドで実行されるべき");
    assertTrue(!orderService.lastNotificationThread().equals(callerThread),
            "通知は呼び出しスレッドで同期実行されるべきではない");
}
```

実行すると2テスト中1テストが失敗し、次の観測になります。

```text
Tests run: 2, Failures: 1, Errors: 0
通知はExecutorスレッドで実行されるべき ==> expected: <true> but was: <false>
```

戻り値は`confirmed:order-1`で正しいため、スレッド名を独立した観測値にしています。完全な出力は [`evidence/01-broken-test-output.txt`](https://github.com/tonbiattack/java-spring-async-self-invocation-lab/blob/main/evidence/01-broken-test-output.txt) に保存しています。

## 調査：何を観測し、どの仮説を除外したか

最初に「`@Async`が無効なのではないか」「Executor設定が同期実行なのではないか」「self-invocationがproxyを迂回しているのではないか」という3つの仮説を比較しました。

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| `@EnableAsync`が無効 | 外部Beanからの呼び出しも呼び出しスレッドになる | `NotificationService`を外部から呼ぶ | `notification-1`になる | 棄却 |
| Executor設定が誤っている | proxy経由でもExecutorスレッドへ移らない | 名前付きExecutorのスレッド名を観測 | `notification-1`になる | 棄却 |
| self-invocationがproxyを迂回する | 同じBean内部の呼び出しだけ同期実行になる | 内部呼び出しと別Bean呼び出しを比較 | 内部だけ呼び出しスレッド | 採用 |

Spring公式リファレンスは、Spring AOPがproxy-basedであることを説明しています。クライアントがproxyを参照してメソッドを呼ぶ場合、proxyが関連するinterceptorを通じて対象へ委譲します。[1]

一方、対象オブジェクトへ呼び出しが到達したあと、そのオブジェクト自身が`this.bar()`や`this.foo()`を呼ぶ場合、呼び出し先はproxyではなく`this`参照です。そのため、self-invocationでは対象メソッドに関連するadviceが実行されません。[1]

今回の不具合状態では、`confirm()`からの次の呼び出しがself-invocationです。

```java
sendNotification(orderId);
```

Javaの構文上は`this.sendNotification(orderId)`と同じ対象へ到達します。`@Async`を付けても、ここにproxyが挿入されるわけではありません。

## 修正：非同期境界を別Beanへ分離する

修正前は、注文サービス自身が非同期通知メソッドを持っていました。

```diff
 @Service
 class OrderService {
+    // 修正前はこのクラス自身に@Asyncメソッドがあった
     public String confirm(String orderId) {
         sendNotification(orderId);
         return "confirmed:" + orderId;
     }
-
-    @Async("notificationExecutor")
-    public void sendNotification(String orderId) {
-        // 通知処理
-    }
 }
```

最小修正では、`@Async`メソッドを`NotificationService`へ移します。

```java
@Service
class OrderService {
    private final NotificationService notificationService;

    OrderService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

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

`OrderService`から`NotificationService`への呼び出しは別Beanの参照を通ります。Springが管理する`NotificationService`のproxyが呼び出しを受け、`@Async`のadviceがExecutorへ委譲できます。

Spring公式リファレンスは、self-invocationを避ける方法として、まずself-invocationが発生しないようリファクタリングすることを挙げています。[1] `AopContext.currentProxy()`を使う方法もありますが、クラスをSpring AOPへ強く結合します。本ラボでは、責務と非同期境界を別Beanで表現する修正を採用しました。

## 回帰テスト

修正後も元の失敗テストを残しています。さらに、別Beanの`NotificationService`を直接呼ぶ対照ケースを残し、proxy経由なら非同期になることを固定します。

| テスト | 固定する契約 |
|---|---|
| `confirm_shouldDispatchNotificationToExecutorThread` | 注文確定からの通知もExecutorスレッドで実行される。 |
| `directProxyCall_runsOnNotificationExecutor` | 別Beanのproxy経由なら`notification-`スレッドで実行される。 |

```bash
git checkout main
mvn clean test
```

修正後の結果は次のとおりです。

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完全な成功出力は [`evidence/02-fixed-test-output.txt`](https://github.com/tonbiattack/java-spring-async-self-invocation-lab/blob/main/evidence/02-fixed-test-output.txt) に保存しています。修正コミットは `c27b8d5` です。

## まとめ

判断規則は3つです。

1. `@Async`はアノテーションだけで内部呼び出しを非同期化するものではなく、Spring AOP proxyを通る呼び出しで効果を発揮します。
2. 同一Bean内の`this.method()`や暗黙のself-invocationはproxyを迂回し、`@Async` adviceを受けません。
3. 非同期境界を別Beanへ分離し、実行スレッドや完了通知をテストで観測すると、設定問題とproxy境界問題を切り分けられます。

## 参考資料

[1]: https://docs.spring.io/spring-framework/reference/core/aop/proxying.html "Proxying Mechanisms — Spring Framework Reference"
[2]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html "Async — Spring Framework Javadoc"
[3]: https://docs.spring.io/spring-framework/reference/integration/scheduling.html "Task Execution and Scheduling — Spring Framework Reference"
