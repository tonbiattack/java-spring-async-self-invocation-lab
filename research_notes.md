# 調査メモ：Spring `@Async` とself-invocation

## 既存題材との重複調査

`tonbiattack/qiita` と既存Javaラボを調査した結果、次の近接題材が存在する。

| 既存題材 | 中心となる契約 | 今回との差分 |
|---|---|---|
| `検査例外なのにDB更新がコミットされた：Spring BootのrollbackForを実際にデバッグする.md` / `spring-checked-exception-rollback-lab` | `@Transactional` のデフォルトrollback規則 | 今回はトランザクションではなく、`@Async` の呼び出しがSpringプロキシを経由するかという非同期実行境界を扱う。 |
| `ログに「保存した」と出ているのにDBでは0件だった：Spring Bootのトランザクション境界を実際にデバッグする.md` / `spring-boot-transaction-debug-lab` | トランザクション境界 | 今回はDBトランザクションではなく、呼び出しスレッドとTaskExecutorの境界を扱う。 |
| 既存のCORS、Cache、JPA、Security教材 | 各機能の設定・状態契約 | `@Async` のself-invocationとプロキシ回避は未確認で、失敗条件・観測方法・修正中心が異なる。 |

## 採用題材

同じBean内の `this.send()` から `@Async` メソッドを呼ぶと、呼び出しは対象Bean自身へ直接到達し、Spring AOPプロキシを経由しない。そのため、外部Beanから呼んだときは別スレッドで実行される処理が、内部呼び出しでは呼び出しスレッド上で同期実行される。

期待する利用者視点の挙動は、注文確定メソッドが通知処理を呼び出した後、通知処理が常にTaskExecutorの別スレッドで実行されることである。不具合状態では、通知の戻り値は正しくても、記録したスレッド名が `main` のままになる。

## 公式資料の根拠

| 資料 | 根拠 | 記事での利用 |
|---|---|---|
| [Spring AOP Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) | Spring AOPはproxy-basedであり、対象オブジェクト内の`this.foo()`や暗黙のself-invocationはproxyを経由せずadviceをバイパスする。 | `@Async`が内部呼び出しで効かない原因。 |
| [`@Async` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html) | `@Async`はメソッドを非同期実行の候補としてマークし、戻り値は`void`または`Future`系に制約される。 | アノテーションの意味とテスト対象。 |
| [Spring Task Execution and Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) | Springの`TaskExecutor`はタスク実行の抽象化で、`SyncTaskExecutor`や`ThreadPoolTaskExecutor`などを提供する。 | 固定Executorでスレッド境界を観測し、テストを決定的にする。 |

## 題材設計

`OrderService.confirm()` が同一クラス内の `this.notificationService.send()` を呼ぶ構成ではself-invocationにならないため、より典型的な誤解を小さく再現するため、同じBean内の `confirm()` から `this.sendNotification()` を呼ぶ構成にする。`sendNotification()` に`@Async`を付け、`ThreadPoolTaskExecutor`を1スレッドで固定する。

観測は次の3点で行う。

1. 外部からproxy経由で`sendNotification()`を呼んだ場合の実行スレッド名。
2. 同じBeanの`confirm()`からself-invocationした場合の実行スレッド名。
3. `@Async`を別Beanへ分割してproxy経由にした修正後の実行スレッド名。

| 仮説 | 予測 | 最小実験 | 判定 |
|---|---|---|---|
| A. `@Async`が付いていれば、どの呼び出しでも別スレッドになる | 内部呼び出しも`notification-1`になる | 同一Beanのself-invocationを観測 | 棄却予定 |
| B. Spring proxyを通った呼び出しだけ非同期になる | 外部proxy呼び出しは`notification-1`、内部呼び出しは`main` | 同一メソッドをproxy経由・self経由で比較 | 採用予定 |
| C. Executor設定が無効で常に同期実行される | 外部proxy呼び出しも`main`になる | 固定Executorと外部proxy呼び出しを観測 | 棄却予定 |

## 前提バージョン

* Java 21
* Spring Boot 3.4.4
* Spring Framework 6.2.x（Boot管理）
* JUnit 5
* 外部サービス・DB・現在時刻・乱数に依存しない。
