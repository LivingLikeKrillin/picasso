# 환경 전제 목록 — 이 일감을 시키려면 현장에 무엇이 있어야 하는가

**이 문서는 검사 명세가 아니라 설계 입력이다.** 로봇을 쓰는 공장·창고를 짓는 쪽이 *"이런 일을 시키려면 물리 세계에 이런 것이 있어야 하는구나"*를 읽어 갈 수 있어야 한다. 제약이자 제안이다.

근거는 §15.81 — **능력은 기체의 성질이 아니라 (기체 × 현장)의 성질이다.** 같은 기체가 현장의 제시 방식에 따라 되기도 하고 안 되기도 한다.

**등급.** 아래 근거는 전부 벤더 1차 자료(Spot 공개 SDK proto 152개 8089심볼, Digit 매뉴얼 416심볼)에서 왔다. 심볼 이름은 `adapter-*/src/test/resources/vendor-manifest.txt`와 대조된다.

---

## 축 하나를 먼저 — 관측 가능한 전제와 사람이 보증하는 전제

| | 뜻 | 확인 |
|---|---|---|
| **관측 가능** | 로봇이나 어댑터가 물어볼 수 있다 | CLAIMED → **CONFIRMED / CONTRADICTED** |
| **보증** | 로봇이 볼 수 없다 | **영원히 CLAIMED** |

**벤더가 이 축을 이미 갖고 있다.** `Staircase.KnowledgeType`이 `KNOWLEDGE_TYPE_MAPPED`(지도에 있다) · `KNOWLEDGE_TYPE_TRACKED_ONGOING`(지금 보고 있다) · `KNOWLEDGE_TYPE_TRACKED_COMPLETED` · `KNOWLEDGE_TYPE_UNKNOWN`을 가른다 — *"전제가 갖춰졌는가"*를 열거로 답하는 것이다.

**둘을 같은 칸에 두면 안 된다.** 확인된 것과 사람이 말한 것이 구별되지 않으면, 화면이 초록인데 현장에서 실패하고 그 실패가 기체 탓으로 보인다.

---

## A. 지도와 정렬 — 커미셔닝

| 전제 | 근거 | 확인 |
|---|---|---|
| GraphNav 지도가 녹화되어 있다 | `GraphNavService.DownloadGraph` | 관측 가능 |
| 자리마다 사람이 이름을 붙였다 | `Waypoint.Annotations.name` (*"Kitchen Fridge"*) | 관측 가능 (`GetKnownSiteNames`) |
| 그 이름이 사람이 붙인 것이다 | `Annotations.waypoint_source` ∈ {`ROBOT_PATH`, **`USER_REQUEST`**, `ALTERNATE_ROUTE_FINDING`} | 관측 가능 |
| 지도를 실세계 좌표에 고정하려면 앵커가 있다 | `graph_nav.Anchoring{anchors, objects}` · `AnchoringHint{waypoint_anchors, world_objects}` | 관측 가능 |
| **녹화 시점과 운영 시점의 환경이 같다** | — | **보증** |

> 지도는 **로봇을 몰고 다녀야** 생긴다. 라인을 개편하면 다시 몰아야 한다. 이것이 배치 비용의 큰 덩어리이고, 도면에서 생성되지 않는다.

## B. 자리의 결속이 그 일감에 충분하다

| 전제 | 근거 | 확인 |
|---|---|---|
| 이동 목적지 — 웨이포인트면 족하다 | `Waypoint.waypoint_tform_ko`(`SE3Pose`)가 **로봇이 설 자리**다 | 관측 가능 |
| **놓을 자리 — 웨이포인트로는 부족하다** | `Annotations`에 조작 대상의 자세가 없다 | §15.79 열림 |
| 자리를 자세째 저작할 수는 있다 | `MutateWorldObjects`(`ACTION_ADD`) · `WorldObject.transforms_snapshot` | 관측 가능 |

> **같은 이름이 `navigate_to`에는 충분하고 `pick_place`에는 모자랄 수 있다.** 선반 6번의 높이는 웨이포인트가 안 문다.

## C. 물건을 어떻게 제시하는가

| 전제 | 결과 | 확인 |
|---|---|---|
| **종류별 용기·지정 자리로 제시한다** | 자리가 신원을 보증한다 → 마킹도 인지도 불필요 | **보증** |
| 혼재로 제시한다 | 인지가 필요하다 → D 또는 E가 딸려 온다 | **보증** |

> **가장 싸게 만드는 전제가 이것이다.** 공정이 종류별로 제시하면 신원 문제가 통째로 사라진다. 고정 워크셀 pick-and-place가 실증에서 먼저 성공한 이유이기도 하다.

## D. 마킹

| 전제 | 근거 | 확인 |
|---|---|---|
| AprilTag(36h11)를 붙였다 | Spot `WorldObject.apriltag_properties.tag_id` · Digit `ObjectSelector.april_tag_id` | 관측 가능 |
| **바코드·QR·RFID는 쓸 수 없다** | Spot 8089심볼에 **0건** | — |

> 로트마다 붙이는 것은 비현실적이다. **재사용되는 용기·팔레트·지그·앵커**에 붙이는 것이 현실적이며, 그러면 태그는 그릇의 신원이고 내용물은 상위 시스템이 안다.

## E. 인지를 배포했다

| 전제 | 근거 | 확인 |
|---|---|---|
| 외부 ML 워커가 떠 있다 | `NetworkComputeBridgeWorker.WorkerCompute` | 관측 가능 (`ListAvailableModels`) |
| 그 모델이 필요한 라벨을 낸다 | `ModelLabels.available_labels`(`repeated string`) | 관측 가능 |
| 신뢰도 문턱을 넘게 보인다 | `NetworkComputeInputData.min_confidence` | 부분 |
| **조명·카메라 각도가 그 모델의 전제를 만족한다** | — | **보증** |

## F. 충전과 도크

| 전제 | 근거 | 확인 |
|---|---|---|
| 도크가 설치되고 id가 배정됐다 | `DockProperties{dock_id, type, frame_name_dock}` · `docking.ConfigRange{id_start, id_end, type}` | 관측 가능 |
| 도크가 지금 쓸 수 있다 | `DockProperties.unavailable` | 관측 가능 |
| 일감이 배터리 하나를 넘으면 복귀 기준이 있다 | Autowalk `Element.battery_monitor` · Orbit `SiteWalk.batteryMonitor` | 관측 가능 |
| **한 도크를 여러 일감이 다투지 않는다** | 벤더 문서가 *"as of 4.1.0, users are responsible for ensuring the physical dock is not taken"*라고 적었다 | **보증** |

## G. 통행 가능성 — 바닥·계단·문

| 전제 | 근거 | 확인 |
|---|---|---|
| 계단이 지도에 있다 | `Staircase.KnowledgeType.KNOWLEDGE_TYPE_MAPPED` | 관측 가능 |
| 바닥 마찰이 충분하다 | `FootState.TerrainState.ground_mu_est` | 관측 가능(사후) |
| **문의 경첩 방향·여는 방향을 안다** | `DoorCommand.AutoGraspCommand{hinge_side, swing_direction}` | **보증**(사람이 설정) |
| 문이 미는 문인가 잡는 문인가 | `AutoPushCommand` vs `AutoGraspCommand` — **후자는 팔이 필요하다** | **보증** |
| 문을 볼 수 있다 | `DoorCommand.Feedback.Status.STATUS_NOT_DETECTED` | 관측 가능(사후) |

> **"문 열기"라는 능력이 문의 물리에 따라 갈린다.** 미는 문이면 팔 없이도 될 수 있고, 손잡이를 돌려야 하면 팔이 필요하다. 경첩이 어느 쪽인지는 로봇이 아니라 **배치하는 사람이 설정에 적는다.** 이것이 이 문서가 존재하는 이유의 가장 선명한 사례다 — 문을 어느 쪽으로 달지가 로봇 능력을 바꾼다.

## H. 금지구역

| 전제 | 근거 | 확인 |
|---|---|---|
| 들어가면 안 되는 곳을 정의했다 | `NoGoRegionProperties` (벤더 주석: *"Property for a **user** no-go"*) | 관측 가능 |

## I. 기체 구성 — 현장이 아니라 배치의 전제

| 전제 | 근거 | 확인 |
|---|---|---|
| 팔이 달려 있다 | `RobotState.manipulator_state` (*"only populated if an arm is attached"*) | 관측 가능 |
| 페이로드가 등록됐다 | `Payload*` (심볼 140) | 관측 가능 |

## J. 무선

| 전제 | 근거 | 확인 |
|---|---|---|
| **명령 경로가 끊기지 않는다** | BD 문서가 *"send short-lived commands and **continuously resend** them so that the robot stops in the event of a client-side issue"*를 요구한다 | 부분 |

> **끊기면 로봇이 선다.** 이것은 결함이 아니라 안전 설계다. 그러므로 무선 음영은 성능 문제가 아니라 **일감이 성립하지 않는 구역**이다.

---

## 아직 안 정한 것

- **전제를 어디에 선언하나.** 프로파일은 기체마다이고 이 전제들은 **일감과 현장의 짝**에 붙는다. 어느 한쪽 문서에 안 들어갈 수 있다(§15.81).
- **보증 항목을 어떻게 기록하나.** 영원히 CLAIMED인 것들이며, CONFIRMED와 같은 칸에 두면 안 된다.
- **A~J의 분류가 맞는지 모른다.** 항목이 하루에 모였고 근거가 Spot에 치우쳐 있다. Digit 쪽은 조건·분기가 없고 인지 훅이 없다는 것까지만 쟀다.
- 안전 격리(사람과 로봇의 공간 분리)는 여기 안 적었다 — [ADR 32](adr/0032-safety-boundary.md)가 안전을 계약 밖으로 둔 것과의 관계를 먼저 정해야 한다.
