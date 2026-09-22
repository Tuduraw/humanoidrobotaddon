# Humanoid Robot Addon

Tudur's Vehicle Mod (`tudursvehiclemod`https://github.com/Tuduraw/tudursvehiclemod) のアドオンMODとして、人型ロボットや多脚機など
「脚で歩く機体」を追加します。前提MODの公開APIとオーバーライド可能なメソッドだけで
実装しており、前提MODへのmixinは使っていません(vanillaへのmixinは飛行時のカメラ用に
2つあります)。

開発の経緯・技術的な知見は [DEVELOPMENT_NOTES.md](DEVELOPMENT_NOTES.md) にまとめています。

## 動作要件

- Minecraft 1.21.11 / Fabric Loader 0.18.x / Fabric API
- tudursvehiclemod: 本アドオン向けに追加された次のフックを含むバージョン(v1.0.2以降)
  - `tudursvehiclemod$getBodyOrientation(float)`(補間付きの機体姿勢)
  - `tudursvehiclemod$getCustomPartTransforms(float)`(パーツごとの変形行列)
  - `tudursvehiclemod$getBodyFrameOffset(float)`(機体フレームの平行移動)
  - `WeaponType.CUSTOM` / `CustomWeaponTypes` / `CustomWeaponBehavior`(武器タイプの拡張)
  - jarに同梱した`assets/<namespace>/weapons/*.txt`の読み込み対応

## 操作

| キー | 既定 | 動作 |
|---|---|---|
| 移動モード切替 | V | 短押し: 通常⇔滑走。長押し: 飛行へ。飛行中の短押し: 滑走へ |
| 上昇 / 下降 | Space / Shift | 通常・滑走: Spaceで上昇(`climb_limit_ticks`で制限可)。飛行(`robot`): Space/Shiftで上昇・下降 |
| 飛行の姿勢(`flight_style: "robot"`) | ← → / ↑ ↓ / A D | ヨー / ピッチ / ロール。視点はマウスで自由 |
| 飛行の姿勢(`flight_style: "aircraft"`) | マウス / A D | 左右=ヨー、上下=ピッチ / ロール。視点は機体に固定(前提MODのフリールックキーで解除) |
| ターゲット指定 | 前提MODの艦載機ターゲット指定キー | 照準付近の候補(オレンジ枠)を、選択中の武装にロック(赤枠)。もう一度押すと解除 |
| ロックモード切替 | N | マルチロック対応の武装で、複数目標⇔単一目標を切り替え |
| 着陸脚 | 前提MODの着陸脚キー | `flight_style: "aircraft"`の飛行中のみ。展開・格納は手動 |

武器の選択・発射・マニュアル照準・フリールックは前提MODのキーをそのまま使います。

## 移動モード

| モード | 速度・加速・燃費 | 動作 |
|---|---|---|
| 通常 | 1x | 二足(多脚)歩行。段差は`step_height`まで自動で乗り越え、Spaceで上昇 |
| 滑走 | `movement.glide`の倍率 | 手足を止めて地上を滑走 |
| 飛行 | `movement.flight`の倍率 | 3次元機動。`flight_style`で操作・物理が変わる(下記) |

飛行モードには重力がなく、入力が無ければその場に留まります(`"robot"`)。
`"aircraft"`では前提MODの航空機と同じ飛行モデル(持続飛行・滑空・失速・上昇で減速・
降下で加速・衝突ダメージ・着陸脚)で飛び、機首の向きで高度を変えます。

## ターゲティング

- **候補**: 照準付近(`lock_cone_degrees`内、`lock_range`まで)の最も近い目標を毎tick探し、
  オレンジの枠で表示します(機体が対象の場合はメッシュ表示、それ以外は当たり判定の箱)。
- **ロック**: 指定キーで候補を選択中の武装にロックします。ロックは武装ごとに独立で、
  赤枠と武装名で表示されます。ロック中の武装はパイロットの視点に関係なく目標を狙います。
- **マルチロック**(`multi_lock`): 指定キーで目標を追加し(上限あり)、1回の発射で
  複数発を目標へ均等に割り振ります。Nキーで単一目標モードに切り替えられます。
- **ミサイル**: 前提MODのミサイルのロックオン(HUDのゲージ)とは独立です。
  `Type = humanoidrobotaddon:multi_missile`の武装は、このアドオンのロックを誘導に使います。
- 弾道補正(`ballistic_compensation`): ロック目標へ撃つ際、弾の落下分だけ上向きに補正します。

## 車両JSON

前提MODの車両JSONに`"humanoid_robot"`オブジェクトを追加します(無ければ既定値)。
サンプルは`src/main/resources/data/humanoidrobotaddon/vehicles/`を参照してください。

### 基本

| 項目 | 既定 | 内容 |
|---|---|---|
| `lock_range` / `lock_cone_degrees` | 96 / 30 | 候補探索の距離と、照準からの角度 |
| `lock_origin_y` | 2.2 | 候補探索の始点の高さ(モデル座標) |
| `highlight_target` | true | 候補の枠線を表示する |
| `ballistic_compensation` / `max_ballistic_compensation_degrees` | true / 45 | 弾道補正の有無と上限 |
| `swing_min_speed` / `swing_full_speed` / `swing_response` | 0.01 / 0.4 / 0.12 | 歩行スイングが始まる速度、最大になる速度、追従の速さ |
| `gait_cycle_speed` | 3.0 | 歩行1サイクルあたりの移動量(前提MODの`wheel_rotation_speed`とは独立) |

### `movement`

| 項目 | 既定 | 内容 |
|---|---|---|
| `climb_speed` | 0.35 | 通常・滑走時のSpace上昇速度(ブロック/tick) |
| `climb_limit_ticks` | -1 | 上昇できるtick数。-1=無制限、0=不可。着地で回復 |
| `glide` / `flight` | — | `speed`・`acceleration`・`fuel`・`turn`の倍率(`{"speed":3,"acceleration":0.33,"fuel":3,"turn":1}`など) |
| `mode_availability` | 2 | 0=通常のみ、1=滑走まで、2=飛行まで |
| `mode_hold_ticks` | 6 | 長押し判定のtick数 |
| `flight_style` | `"robot"` | `"robot"` / `"aircraft"`(上記「操作」参照) |
| `flight_yaw_rate_degrees_per_tick` / `flight_pitch_rate_degrees_per_tick` / `flight_roll_degrees_per_tick` | 6 / 12 / 4 | `"robot"`の回転速度 |
| `flight_rotation_smoothing` | 0.2 | `"robot"`の回転の滑らかさ |
| `flight_level_assist_grace_ticks` / `flight_level_assist_smoothing` | 100 / 0.02 | 無入力時に水平へ戻し始めるまでの時間と速さ |
| `flight_vertical_speed` | 0.4 | `"robot"`の飛行中のSpace/Shift速度 |
| `flight_disabled_seats` / `flight_only_seats` | [] / [] | 飛行中に使えない / 飛行中だけ使える武装の`seat_index` |

### `channels`(武装パーツの動き)

`weapon_parts`の`seat_index`ごとに、そのパーツの動かし方を決めます。パーツは
`pilot_fallback: true`にしてください。

| 項目 | 既定 | 内容 |
|---|---|---|
| `seat_index` | 必須 | 対象の`weapon_parts`(および同じ`seat_index`の`weapons`) |
| `mode` | `none` | `lock`: 自分のロック目標のみ狙う。`assist`: ロックが無ければ候補を狙う。`none`: 照準しない |
| `swing` / `amplitude` / `phase` / `cycle_scale` | false / 30 / 0 / 1 | 歩行スイング(度、位相、周期の倍率) |
| `glide_pose` / `flight_pose` | なし | そのモードで照準していないときの角度(`{"yaw":0,"pitch":25}`) |
| `stow_when_inactive` / `stow_pose` | false / なし | 選択中・ロック中だけ構え、それ以外は`stow_pose`の姿勢 |
| `multi_lock` | なし | `{"max_targets":4,"salvo_size":8}`。マルチロックを有効化 |

### `joints`(関節)

`weapon_parts`ではないパーツを、親子関係付きの関節として動かします。

| 項目 | 既定 | 内容 |
|---|---|---|
| `part` | 必須 | OBJのグループ名 |
| `parent` | なし | 親の`part`名(関節または`weapon_parts`のパーツ) |
| `pivot_x/y/z` / `axis_x/y/z` | 0 / (1,0,0) | 回転中心と回転軸 |
| `rest_angle` | 0 | 基本角度 |
| `gait` | なし | `{"amplitude":30,"phase":0,"cycle_scale":1}` |
| `glide_pose` / `flight_pose` | 0 | モード別に加算する角度 |
| `min_angle` / `max_angle` | -180 / 180 | 可動範囲(膝を一方向にだけ曲げるなど) |
| `ready_seat` / `ready_angle` | なし / 0 | そのチャンネルが構えている間の角度(肘など) |
| `spin_speed` | 0 | 移動距離に応じた回転(度/ブロック、転輪など) |
| `terrain_axis` | なし | `"pitch"` / `"roll"`。`terrain_follow`の角度を加える |

### `terrain_follow`(地形追従)

`{"front_z":1.2,"back_z":-1.2,"half_width":0.75,"max_pitch":20,"max_roll":15,"response":0.35}`。
4点の地面の高さから傾きを求め、`terrain_axis`を持つ関節に渡します。

### `mode_visuals`(見た目のモード連動)

| 項目 | 既定 | 内容 |
|---|---|---|
| `transition_speed` | 2.0 | モード切替の見た目の速さ(進捗/秒) |
| `parts` | [] | `toggle_parts`のパーツの開閉度をモード別に指定(`{"part":"$wing","walk":0,"glide":0,"flight":1}`) |
| `flight_body_pitch` | 0 | 飛行時の前傾角(見た目・武装の取り付けに反映、推力・視点は不変) |
| `body_pitch_pivot_y/z` | 0 | 前傾の回転中心 |
| `lean_follow_seats` | [] | 姿勢変化に追随させる(照準を補正しない)武装の`seat_index` |

`toggle_parts`の`trigger`に`landing_gear`を指定すると、着陸脚の展開に連動します
(`flight_style: "aircraft"`のみ)。

## 武装ファイル

`assets/humanoidrobotaddon/weapons/*.txt`(前提MODの形式)。`Type = humanoidrobotaddon:multi_missile`
はこのアドオンが登録する武器タイプで、マルチロックの誘導・斉射を行います。
`ModelBullet`用の弾モデルを3種類(`shell`・`heavy_shell`・`missile`)同梱しています。

## 同梱の機体

| Tier | 機体 | 概要 |
|---|---|---|
| 1 | `spider_light_tank` | 4脚軽戦車。砲塔+57mm砲。段差2ブロック。滑走・飛行・上昇なし |
| 2 | `spider_heavy_tank` | 4脚重戦車。2連装105mm砲、追尾ミサイル。滑走のみ、上昇1秒 |
| 3 | `sample_walker` | 人型。ライフル、肩ミサイル、マルチロックミサイル。全モード |
| 4 | `tread_walker` | 履帯脚部。ライフル、肩ミサイル、背負式120mm砲(選択時展開)。地形追従、滑走のみ |
| 5 | `quad_flyer` | 4脚に人型の上半身。飛行時に脚を広げて浮遊 |
| 5 | `transformer_jet` | 変形機(`flight_style: "aircraft"`)。背中の翼を展開 |
| 5 | `variable_fighter` | 変形機。飛行形態で機首・主翼を展開、着陸脚あり |

モデルは`tools/`のスクリプトで生成しています(`make_sample_walker.py`、`make_vehicles.py`)。

## HUD

`assets/humanoidrobotaddon/hud/humanoid_robot.txt`(前提MODのHUDスクリプト形式)。
照準、武装名・残弾・ヒート、ミサイルロックのゲージ、機体の状態、着陸脚を表示します。

## ビルド

前提MODで`./gradlew publishToMavenLocal`を実行してから、このディレクトリで
`./gradlew build --refresh-dependencies`を実行してください。Minecraft / Fabric Loader /
Yarnのバージョンは前提MODと一致させます(`gradle.properties`)。

---

コード・ドキュメントの生成にClaudeを使用しています。
