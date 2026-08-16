# Spring `@Async` self-invocation デバッグラボ

このプロジェクトは、同じSpring Bean内から `@Async` メソッドを呼び出すと、呼び出しがSpring AOPプロキシを迂回し、非同期実行にならない挙動を再現する教材です。

`confirm()` の戻り値は正しくても、通知が呼び出しスレッドで同期実行されるため、処理時間、スレッド分離、リクエストの応答時間に関する期待が崩れます。

## 前提

| 項目 | 固定値 |
|---|---|
| JDK | 21 |
| Spring Boot | 3.4.4 |
| Spring Framework | Spring Boot管理の6.2.x |
| テスト | Spring Boot Starter Test / JUnit Jupiter |
| Executor | `ThreadPoolTaskExecutor` 1スレッド、`notification-`接頭辞 |

外部サービス、DB、現在時刻、乱数に依存しません。Executorのスレッド名を観測値として使います。

## 不具合状態の再現

```bash
mvn test
```

不具合状態では、`confirm_shouldDispatchNotificationToExecutorThread` が失敗します。`confirm()` から同じBeanの `sendNotification()` を直接呼ぶため、実行スレッドはテストの呼び出しスレッドです。

期待は `notification-1` ですが、実際には `main` またはJUnitが使う呼び出しスレッド名になります。

## 修正後の検証

```bash
mvn clean test
```

修正後は、`OrderService` から `@Async` メソッドを分離し、別のSpring Beanである `NotificationService` へ委譲します。`OrderService`から見た呼び出しは別Beanのproxyを経由するため、通知は `notification-1` で実行されます。

## 構成

```text
src/main/java/jp/tonbiattack/debuglab/AsyncDebugApplication.java
src/main/java/jp/tonbiattack/debuglab/AsyncConfig.java
src/main/java/jp/tonbiattack/debuglab/OrderService.java
src/main/java/jp/tonbiattack/debuglab/NotificationService.java  # 修正後に追加
src/test/java/jp/tonbiattack/debuglab/OrderServiceTest.java
docs/investigation.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
research_notes.md
```

## 重要な判断

`@Async` はメソッドに注釈を付けるだけで、`this.sendNotification()` の呼び出しを別スレッドへ変換する機能ではありません。Spring AOPのproxyが呼び出しを受け、Executorへ委譲することが必要です。

したがって、最小修正は `@Async` を追加し直すことではなく、非同期境界を別Beanのメソッド呼び出しとして設計することです。Spring AOPに依存しない設計にしたい場合は、`TaskExecutor` を明示的に注入してタスクをsubmitする方法もありますが、本ラボでは `@Async` のproxy境界を検証するため、別Beanへの分割を採用します。

## Git履歴

最初のコミットはself-invocationによる失敗テストと観測ログ、次のコミットは別Beanへの分割修正と回帰テスト、最後のコミットは記事下書きとします。
