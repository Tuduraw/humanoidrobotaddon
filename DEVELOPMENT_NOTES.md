# 開発ノート — humanoidrobotaddon

今後のアドオン開発のための技術資料です。実装内容の説明は [README.md](README.md) を参照してください。
ここには、設計の判断とその理由、前提MOD(`tudursvehiclemod`)の調査で分かった内部仕様、見つけた
不具合、はまりやすい点をまとめています。

---

## 1. 前提MODとの役割分担

### 方針

- **前提MODへのmixinは使わない。** 公開APIとオーバーライド可能なメソッドだけで実装する
  (`motorcycleaddon`と同じ方針)。必要に応じて使用する可能性はある。
- **前提MODの変更は、アドオン固有の都合ではなく「前提MOD自体の不具合」または「他のアドオンにも
  有用な汎用フック」の場合に限る。** 判断基準: そのアドオンだけのために前提MODを変えるのは不適切。
  複数のアドオンで再利用できる差し込み口、あるいは前提MODの仕様と実装の食い違いなら価値がある。
- **`AircraftEntity`は共通化しない**(前提MODの方針)。航空機と同じ挙動が欲しい場合は移植する。

### 前提MODに追加したフック(いずれも既定値では既存の挙動を変えない)

| フック | 目的 |
|---|---|
| `AbstractVehicleEntity.tudursvehiclemod$getBodyOrientation(float tickDelta)` | 補間付きの機体姿勢。`VehicleEntityRenderer`が3つのオイラー角の代わりにこれを使う(ジンバルロックのジッター解消) |
| `tudursvehiclemod$getCustomPartTransforms(float)` | OBJグループ名→モデル座標系の変形行列。関節(多段の連結)のための汎用フック。レンダラーは行列を適用するだけで関節の概念を持たない |
| `tudursvehiclemod$getBodyFrameOffset(float)` | 機体フレームの平行移動。描画位置・座席・目の位置・弾の発生位置の4箇所に前提MOD自身が適用する |
| `WeaponType.CUSTOM` / `CustomWeaponTypes` / `CustomWeaponBehavior` | 武器タイプの拡張。`Type = <namespace>:<id>`で登録済みの挙動(ロック対象・斉射数・1発ごとの誘導先)を差し替える |

### 前提MODで見つけて修正した不具合

| 不具合 | 内容 |
|---|---|
| jarに同梱した武装ファイルが読み込まれない | `WeaponStatsLoader`が`ResourceType.SERVER_DATA`で登録されており、`SERVER_DATA`の`ResourceManager`は`data/`しか見えない。ドキュメント通り`assets/<ns>/weapons/`に置いたファイルは一度も読まれず、全武装が既定値(表示名"Weapon"、弾数無制限、`Type=OTHER`)で動いていた。`ServerObjModelHitboxes`と同じ「`FabricLoader`で全modのルートを直接たどる」方式を武装にも適用して修正 |
| `pilot_usable`武装のミサイルロックが始まらない | 発射処理は「座席が空なら`pilot_usable`ならパイロット」を認めるのに、ロックオン側は座席の占有者しか見ていなかった。ロックオン側も同じ`resolveWeaponTrackingOccupant()`を使うよう修正 |


---

## 2. 前提MODの内部仕様(調査で分かったこと)

### 座標系と符号

- モデル座標: **+Zが前方、+Yが上、y=0が接地面**。機体が+Zを向くとき、**+X側が機体の左、-X側が右**
  (南を向くと東が左手側になるのと同じ)。`sample_walker`の`$arm_r`は歴史的経緯で+X(=左腕)。
- カメラ座標: 前方は**-Z**。モデル座標とは単純な180度回転の関係ではない(ピッチ・ロールの符号も
  内部で異なる)。カメラの向きは前提MODの`CameraMixin`の実績ある式
  `rotateY(180-yaw).rotateX(-pitch).rotateZ(-roll)`をそのまま使うのが安全。
- 引きの方向(三人称の位置)は、カメラの向き`fresh`から`fresh.transform(0,0,+1)`で求めると、
  背後・正面(`inverseView`)のどちらでも正しくなる。別のクォータニオンを組み立てて求めようとすると、
  見回し量との合成順序の違い(非可換)で食い違う。
- ピッチの符号: Minecraftの規約(負が上)。機体本体の入力は`rotateX(+pitch)`、カメラ座標系では
  `rotateX(-pitch)`。見回し量のように**カメラ座標系に合成する値は符号を反転**する必要がある。
- 関節のX軸回りの正の角度は、垂れ下がった部品を**後方へ**振り上げる方向。

### `FreeCameraVehicle`と`CameraMixin`

- `FreeCameraVehicle`(5メソッド、すべて`AbstractVehicleEntity`が既に持つ)を実装すると、前提MODの
  `CameraMixin`・`PlayerLookRateMixin`・`LivingEntityRendererMixin`の対象になる。
- `setFreeLook()`は`FreeCameraVehicle`でない機体では**無条件にno-op**。実装しないとフリールックキーは
  一切効かない。
- `CameraMixin`は`isEffectiveFreeLook()`で2分岐する。**ロック分岐**は毎フレーム
  `getYaw/getPitch/getRoll(tickProgress)`からカメラを組み立てる(滑らか、視点は機体固定)。
  **フリールック分岐**はvanillaの視点をそのまま使い、機体のロールだけ捻る(ヨー・ピッチは機体に
  追随しない)。「自由に見回せて、かつ機体に追従する」はどちらの分岐でも実現できない。
- 前提MODの航空機用マウス入力(`PlayerLookRateMixin`)は`usesAircraftStyleOrientation()`で
  `AircraftEntity`等をクラス名指しで判定しており、アドオンの機体は加われない。

### 武装

- `weapon_parts`と`weapons`は`seat_index`で照準角(`getWeaponAimYaw/Pitch`)を受け取る。この2つの
  メソッドをオーバーライドすると、ピボット回転・可動範囲・旋回速度・親子2段合成・発射方向の
  すべてを、再実装せずに別の照準源へ向けられる。
- 前提MODの照準角は「世界のヨー−機体のヨー」「ピッチは世界の値そのまま」。直立した機体では
  正確だが、機体のピッチ・ロールは考慮していない。
- `child_info`は1つの照準を「砲塔の左右+砲身の上下」に分けるための仕組みで、独立して曲がる関節や
  3段以上の連結は表現できない。
- 弾の発生位置は`tudursvehiclemod$computeWeaponSpawnPos()`1箇所で計算され、パーツの回転に追従する。
  回転中心をパーツの端に置いてモード別姿勢で回転させると、見た目と砲口の両方が移動する。
- 武装ファイル(`.txt`)は前提MODが読み込み、知らない項目は読み捨てる。アドオン固有の設定
  (同時ロック数など)は機体JSONに置く。
- 武器タイプ(`WeaponType`)はJavaの列挙型で、`CUSTOM`と登録口を追加するまで外部から拡張できなかった。
- 前提MODの強調表示(`setEntityHighlighted`)はエンティティごとに1つのオン/オフ状態を全システムで
  共有する。アドオン側から流用すると、前提MODのミサイルロックオン表示と互いに消し合う。
  **アドオン独自の表示は自前で描く**(`GizmoDrawing`)。
- 車両の当たり判定は衝突用にモデルより意図的に大きい。強調表示は前提MODの
  `TargetHighlightRenderer`と同じくメッシュの三角形を描画する(箱ではない)。三角形数が
  `MAX_TRIANGLES_PER_DRAW`(前提MODは400)を超えたら等間隔の間引きで全体をカバーする
  (先頭からの切り詰めだと片側だけになる)。`ServerObjModelHitboxes.getMesh(def.model())`と
  `tudursvehiclemod$getCurrentRotationForRendering()`はどちらも公開されているため、
  アドオン独自の強調表示にもそのまま使える。

### 前提MODの再読み込み

- 機体JSON: `data/<ns>/vehicles/`(`SERVER_DATA`)。HUDスクリプト: `assets/<ns>/hud/`
  (`CLIENT_RESOURCES`、ファイル名がキー)。武装: `assets/<ns>/weapons/`(上記の修正後)。
- `assets/`と`data/`はvanillaの再読み込みの上で別の名前空間。**サーバー側で`assets/`配下を
  読むには`FabricLoader.getInstance().getAllMods()`で各modのルートを直接たどる**
  (前提MODの`ServerObjModelHitboxes`が先例)。

### その他

- `tudursvehiclemod$updateWheelSpinPhase()`は`AbstractVehicleEntity.tick()`が
  `updateVehicleMovement()`の後に呼ぶ。`getActualForwardSpeed()`はその前提で動く。
- `cruiseSpeed`は`approachThrottledVelocity()`が内部でブレンド管理する共有値。
  `CarEntity`が実測値で上書きしているのは「無操縦・自動操縦なし・クライアント側」の
  航跡描画用の限定的な分岐だけ。
- 着陸脚: `GEAR_DEPLOYED`(同期)と`landingGearProgress`(緩和、0=展開)。接地時に前提MODが自動で
  展開する。`landing_gear`トリガーの`toggle_parts`は緩和値を直接読む(getter経由ではない)。
- 前提MODの`Camera.update()`へのmixinは、既定優先度(1000)。同じTAILに差し込む場合は
  優先度を上げれば後に実行される。

---

## 3. 設計判断とその理由

### `AbstractVehicleEntity`を直接継承(`CarEntity`ではない)

`CarEntity`は車体全体を斜面に合わせて傾け、ジャンプキーをブレーキに割り当てる。ロボットは脚で
斜面を吸収して胴体を水平に保ち、ジャンプキーは上昇に使う。構造的に逆なので、`CarEntity`の
共有ヘルパー(`updateThrottle`・`approachThrottledVelocity`・`stepTowardAngle`)だけ使って
地上移動を自前で実装した。

### 3つの移動モードは`VehicleDefinition`のコピーで倍率をかける

`getDefinition()`をオーバーライドしてモード別に最高速度・加速・燃費・旋回速度をスケールした
コピーを返す。HUD・燃料計・旋回制限・巡航速度のブレンドがすべて一度に追従する。

### 飛行姿勢はクォータニオン

ヨー・ピッチ・ロールを毎tick分解→再合成するとピッチ±90°付近でジンバルロックのジッターが
出る。姿勢は`Quaternionf`に入力の回転を積み上げ、`getYaw/getPitch/getRoll`は必要なときだけ
抽出する(倍精度で抽出すると特異点への接近を遅らせられるが、根本解決ではない)。描画は
`getBodyOrientation(float)`で補間済みクォータニオンを直接使う(前提MODへの追加フック)。

### 飛行の姿勢入力はキー(`"robot"`)

当初マウスでピッチを操作したが、視点操作と機体操作が競合した(カメラの上書きが無い状態で
マウスが両方を動かす)。矢印キーに移し、視点はマウスで自由にした。

### カメラ: 「自由に見回す」+「機体に追従」+「滑らか」の3つを両立する

- 1tickごとに視点へ回転量を加算する方式(Car/Shipと同じ)は、平面的で回転の遅いCar/Shipでは
  問題にならないが、3軸で毎tick最大12°回る飛行では20Hzの段差として見える。
- `CameraMixin`の2分岐は「自由」か「追従」のどちらか一方しか提供しない。
- 解決: **機体の姿勢(補間済み)と、パイロットの「見回し量」を別々に持ち、毎描画フレームで
  クォータニオンのまま合成**する。vanillaの`Camera`へのmixin(`RobotCameraMixin`)と、
  `changeLookDirection`へのmixin(`RobotHeadLookMixin`、見回し量の蓄積)の2つ。
- 見回し量は**ヨー・ピッチの独立したスカラー**で持ち、毎フレーム`Ry(yaw)×Rx(pitch)`で組み立てる。
  クォータニオンに逐次乗算で蓄積するとロールへドリフトする(FPS視点の定番の不具合)。
- 見回し量は前提MODの機体フレームには含めない(パイロットの`yaw/pitch`フィールドも書き換えない)。
  そのため**武装の候補探索は`pilot.getRotationVec()`では正しくない**。見回し量をサーバーへ送り
  (`RobotHeadLookPayload`)、カメラと同じ式で方向を求める。
- `isEffectiveFreeLook()`は常にtrueにして`CameraMixin`のフリールック分岐を素通りさせ、
  優先度を上げた`RobotCameraMixin`が後から上書きする。
- 三人称の引き位置は、カメラの向き`fresh`から直接導出する(§2参照)。

### 飛行時の姿勢変化(前傾)と武装の照準

- 前傾は**機体フレーム**(`getBodyOrientation()`)に含め、機体の姿勢そのもの
  (`getYaw/getPitch/getRoll`)には含めない。推力・視点・候補探索は不変で、描画・座席・武装の
  取り付けと発射方向・フレアが追従する。
- 回転だけでは原点(足元)中心にしか回れないので、`body_pitch_pivot`で回転中心を指定し、
  平行移動分を`getBodyFrameOffset()`で返す。
- 照準する武装は**目標値を機体フレームへ補正**する(世界方向を機体フレームの逆回転で機体
  ローカルへ変換し、そこからヨー・ピッチを読み直す)。補正するのは目標値だけなので、旋回速度・
  描画・反動・発射方向は前提MODのまま一致する。前傾だけでなく飛行中のピッチ・ロールにも効く。
- 描画時に逆回転で打ち消す方式は、見た目だけ補正されて発射方向とずれるため廃止した。
- `lean_follow_seats`で補正を外せる(胴体固定の武装)。

### ターゲティング

- 前提MODのミサイルロック・艦載機指定と同じ**2段階**(候補→確定ロック)。
- ロックは**武装ごと**(`seat_index`キー、`"seat:id|id,seat:id"`で同期)。指定キーは前提MODの
  艦載機ターゲット指定キーを流用する(`toggleCarrierLockMode()`をオーバーライド)。
- チャンネルは自分の武装のロックだけを見る。`mode`はロックが無いときのフォールバックだけを
  決める(`assist`=候補、`lock`=何もしない)。
- 候補の枠線・ロックの赤枠はアドオン独自の描画(前提MODの強調表示は使わない、§2参照)。
- マルチロック: 目標リスト(古い順)、斉射を古い順にラウンドロビンで割り振る。`Type =
  humanoidrobotaddon:multi_missile`を`CustomWeaponTypes`に登録して実現する。

### 関節(多段の手足)

順運動学。各関節の行列は`親の行列 × T(pivot) × R(axis, angle) × T(-pivot)`で、親から順に
積み上げる(再帰+メモ化、JSONの記述順は問わない、循環は「親なし」扱い)。角度は
`rest + 歩行スイング×歩幅係数 + モード別姿勢×モードの重み`をクランプし、その後に地形追従と
自由回転(転輪)を加える。親には`weapon_parts`のパーツも指定でき、その場合は前提MODの描画と同じ
順序(親段→自身の回転→反動)で行列を組み立てる。

### 歩行スイングと転輪は別の数値

`wheel_rotation_speed`(車輪、30程度)と`gait_cycle_speed`(脚、3程度)は桁が違う。同じ累積
距離を別々にスケールする。

### 武器を構える・下ろす(`stow_when_inactive`)

「使っているときだけ構える」の判定はロック・選択武器・搭乗状態(すべて同期済み)から行う。
そのモードで使えない武装(`flight_disabled_seats`など)は、チャンネルの種類にかかわらず常に
下ろした姿勢にする(パイロットの視点追従へ戻さない)。

### 変形ロボット(`flight_style: "aircraft"`)

- 当初は専用のエンティティタイプにしたが、前提MODのスポナーは「1カテゴリ=1エンティティタイプ」で
  照合するため、同じTierに並べられない。操作方式の違いは設定で表現できるので、機体JSONの
  `flight_style`に変更した。
- マウス入力: `RobotHeadLookMixin`が見回しの代わりに機体の姿勢入力として蓄積し、矢印キーと同じ
  `RobotOrientationInputPayload`で送る。姿勢の計算(入力をクォータニオンに積み上げる)は航空機と
  元々同じ。
- 視点固定: `isEffectiveFreeLook()`を前提MOD既定(パイロットのフリールックトグル)に戻すだけで
  `CameraMixin`のロック分岐が働く。
- 飛行物理: `AircraftEntity`のパイロット操縦時の飛行モデル(持続飛行・滑空・失速・上昇減速・
  降下加速・協調旋回・衝突/着地ダメージ・着陸脚の効果)を、同じ定数のまま移植した。
  ドローン・編隊・CAS・艦載機・出現直後の猶予・着水・航空機の地上姿勢は含めない。
  **前提MODで航空機の調整値を変えたら、`AIRCRAFT_`定数にも同じ変更が必要。**
- 姿勢処理には「強制的に向ける姿勢」を渡せる引数を設け、失速時の機首下げ・水平復帰・
  地上での水平保持を、既存の姿勢処理(抽出・搭乗者視点の同期を含む)の1経路で行う。
- 着陸脚: 前提MODの仕組み(キー・トリガー・緩和・HUD)をそのまま使い、飛行モード以外では
  常に格納、飛行中も手動のみ。前提MODの接地時自動展開は毎tick打ち消す。緩和値を直接読む
  `landing_gear`トリガーのパーツにも、格納の規則を`getTogglePartProgress()`側で適用する。
- モード切り替え直後は衝突・着地のダメージ判定を20tick無効化する。判定は「その1tickの衝突
  フラグと速度」だけを見るため、切り替え時の不連続(`cruiseSpeed`の引き継ぎ、姿勢の再設定、
  接地判定のタイミング)を実際の衝突と区別できない。航空機の出現直後の猶予と同じ考え方。
  切り替え時の`cruiseSpeed`は実測速度で置き換える。

---

## 4. 見つけた不具合と原因(アドオン側)

| 症状 | 原因 |
|---|---|
| 滑走で巻き戻る・勝手に減速する | `updateGroundMovement()`末尾で毎tick`cruiseSpeed`を実測値で上書きしていた。`CarEntity`の限定的な分岐を条件なしで真似たため、ブレンド計算にノイズが毎tickフィードバックされ、高速の滑走で顕在化した |
| 上昇して段差を越えても前進できない | 「接地していない間は入力を見ず、速度を減衰するだけ」の処理。意図的な上昇を「不慮の落下」と区別していなかった |
| 飛行モードに入ると視点が跳ぶ | `getYaw()`のオーバーライドが初期化前のクォータニオンを自己参照していた。初期化は`super.getYaw()`から行う |
| 三人称で機体が消える・回転中心がずれる | 引き方向をカメラ座標系の`fresh`で`(0,0,-1)`から求めていた(符号・規約の取り違え)。次に別のクォータニオンを組んだが、見回し量との合成順序が`fresh`と異なり非可換で食い違った。`fresh.transform(0,0,+1)`で解決 |
| 見回すと視界が傾く | 見回し量をクォータニオンへ逐次乗算で蓄積していた(ロールへのドリフト) |
| 見回すと武装が過剰に動く | 候補探索が`pilot.getRotationVec()`を読んでいた。見回し量が機体フレームに反映されないため、カメラの見た目と無関係に動く |
| 飛行中に腕が構える | 関節の`flight_pose`に`rest_angle`を打ち消す値を書いていた(設定ミス) |
| 武器名・残弾がHUDに出ない、ミサイルが誘導しない | 武装ファイルが読み込まれていなかった(§1、前提MODの不具合)。これが分かるまで、ロックオンの射手解決の修正(それ自体は妥当)を先に行っていた |

---

## 5. はまりやすい点・作業上の教訓

- **前提MODの一部だけを真似ない。** `CarEntity`の`getActualForwardSpeed()`の流用のように、元の
  コードが置いている条件を落とすと別の不具合になる。分岐の条件まで含めて読む。
- **2つの表現を並行して持たない。** カメラの向きと引き方向、機体本体の姿勢と描画の前傾など、
  同じものを表す値を2箇所で組み立てると、規約や合成順序の違いで食い違う。1つの値から導出する。
- **クォータニオンの合成は非可換。** 「Aを先に、Bを後に」の順序が式ごとに違うと、恒等回転の
  ときだけ一致して、実際に動かすとずれる。
- **`str_replace`系の編集は結果を確認する。** 一致しなかった置換が黙って落ちて、Codecだけ追加
  されてレコード本体が無い、という状態でビルドエラーになったことがある。編集後に括弧の対応や
  参照の有無を確認する。
- **`publishToMavenLocal`後は`--refresh-dependencies`。** バージョン番号を変えずに前提MODを
  更新すると、Gradleが古い成果物を使い続ける。
- **左右の呼び方。** +Xが機体の左。既存の`$arm_r`は左腕(互換性のため改名していない)。
- **`isEffectiveFreeLook()`を変える前に`CameraMixin`の分岐を読む。** trueは「自由だが追随しない」、
  falseは「追随するが自由でない」。
- **前提MODの`ResourceType`。** `assets/`に置いたファイルは`SERVER_DATA`の再読み込みでは見えない。
- **見た目だけの補正は照準とずれる。** 描画時に何かを打ち消すより、目標値の側を補正する。
- **モード切り替えは不連続。** 判定が「その瞬間の値」だけを見る処理は、切り替え直後の猶予を
  設ける。

---

## 6. ファイル構成

```
src/main/java/.../humanoidrobotaddon/
  HumanoidRobotAddon.java        エントリ。エンティティ・ペイロード・武器タイプの登録
  asset/                         機体JSONの"humanoid_robot"設定(Codec)
    RobotSettings / MovementSettings / TrackingChannel / Joint / TerrainFollow / ModeVisualSettings
    MovementMode, RobotSettingsRegistry(再読み込み)
  entity/HumanoidRobotEntity.java 本体。移動・姿勢・照準・関節・飛行モデル
  item/                          スポナーと前提MODのコンバータ連携
  network/                       C2Sペイロード(モード切替・姿勢入力・見回し量・ロックモード)
  weapon/MultiMissileBehavior    マルチロックミサイルの武器タイプ
src/client/java/.../client/
  HumanoidRobotAddonClient       キー割り当て、入力の送信
  LockMarkerRenderer             候補・ロックの枠線(GizmoDrawing)
  HeadLookOffsetState / AircraftFlightInputState  クライアント側の入力状態
  mixin/RobotHeadLookMixin       マウス移動の捕捉(見回し / aircraftの姿勢入力)
  mixin/RobotCameraMixin         飛行時のカメラ(機体姿勢×見回し量)
tools/                           モデル・テクスチャの生成スクリプト
```
