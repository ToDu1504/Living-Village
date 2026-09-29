# Living Villages

*[English](README.md)*

Mod Fabric cho Minecraft 1.21.1, chạy phía server, giúp làng dân tự phát triển. Khi làng hết giường trống, một dân làng trở thành thợ xây, đi tới một chỗ trống gần chuông làng và xây dần từng khối một ngôi nhà mới. Mẫu nhà lấy từ chính bộ nhà của làng nên luôn đúng phong cách. Nếu có cài Better Village thì nhà mới theo mẫu của Better Village.

Mod không thêm khối, vật phẩm, sinh vật hay texture mới. Người chơi không cần cài mod ở máy mình.

## Yêu cầu

- Minecraft **1.21.1**
- Fabric Loader **0.16.0** trở lên
- Fabric API (bản build dùng 0.116.17+1.21.1)
- Java 21

## Cài đặt

1. Cài Fabric Loader cho 1.21.1.
2. Chép `livingvillages-<phiên bản>.jar` và Fabric API vào thư mục `mods`. Với server riêng thì chỉ server cần cài.
3. Mở game một lần. File `config/livingvillages.json` sẽ tự được tạo với giá trị mặc định.

Mod tự chạy ngay sau khi cài, không cần gõ lệnh để bật.

## Cách hoạt động

- **Làng** được nhận diện qua chuông (điểm tụ họp). Mỗi chuông là một làng. Phong cách làng (đồng bằng, sa mạc, xavan, tuyết, taiga) lấy theo dân làng ở lần đầu mod thấy làng và không đổi về sau. Dân làng rừng rậm và đầm lầy được tính là đồng bằng.
- **Làng tự khởi công nhà mới** khi đủ tất cả điều kiện:
  - có ít nhất 2 dân làng trưởng thành;
  - hết giường trống (xem `freeBedThreshold`);
  - số nhà đã xây ít hơn `maxHousesPerVillage`;
  - đã qua một ngày Minecraft (`cooldownTicks`) kể từ nhà trước.
- **Mẫu nhà** được đọc lúc chạy từ bộ `minecraft:village/<phong cách>/houses`. Chỉ dùng nhà có giường và không có khối nghề, nên không xây lò rèn hay nông trại. Datapack và mod sửa bộ nhà này sẽ tự được áp dụng.
- **Chỗ xây** là đất tự nhiên bằng phẳng, khô ráo, cách chuông 12–64 khối. Mod không xây đè lên đường, công trình, giường, chuông, khối nghề hay đất của nhà khác. Chỗ hơi dốc sẽ có móng đỡ (đá cuội, riêng làng sa mạc là sa thạch).
- **Cây:** tối đa 4 cây tự nhiên mọc trong chỗ đặt nhà được chặt trước, không rơi đồ, và chỗ không có cây luôn được ưu tiên. Cây chỉ tính là tự nhiên khi có lá tự nhiên (không phải lá do người chơi đặt), nên nhà gỗ và cây trang trí của người chơi không bao giờ bị đụng tới. Cây khổng lồ và cây có tổ ong được giữ nguyên. Xây xong, mỗi cây đã chặt được trồng lại một cây non cùng loại, cách nhà 3–8 khối; nếu làng có nông dân thì một nông dân đi tới trồng.
- **Thứ tự xây:** móng, rồi dọn cỏ và san đất, rồi dựng nhà từng tầng từ dưới lên, cuối cùng mới đặt cửa, giường, đuốc, thảm và đồ trang trí. Khối chỉ được đặt vào ô trống hoặc ô có thứ thay thế được như cỏ. Khối người chơi đặt chắn đường được giữ nguyên. Rương không có đồ bên trong.
- **Thợ xây:** ưu tiên dân thất nghiệp, rồi thợ đá, rồi bất kỳ ai, trừ dân ngốc (nitwit) và trẻ con. Thợ xây phải đứng trong phạm vi `builderReach` khối mới đặt được, có vung tay và cầm khối đang đặt. Nếu thợ xây chết hoặc 60 giây không tới được công trường thì người khác thay. Không còn ai thì nhà tự xây với nửa tốc độ.
- **Không cần vật liệu:** mod tự tạo khối, thợ xây không phải đi gom vật liệu và không lấy đồ của làng hay của người chơi.
- **Tạm dừng** vào ban đêm, khi làng bị raid, và khi không có người chơi nào trong phạm vi `activeRange` khối. Làng ở xa thì dừng hẳn, không xây bù. Tiến độ được lưu lại, nên thoát game giữa chừng thì lần sau xây tiếp đúng chỗ.
- Nếu chuông bị phá, làng chuyển sang ngừng hoạt động và dự án tạm dừng. Đặt chuông lại gần đó thì làng hoạt động trở lại.

## Ngôn ngữ

Người chơi không cần cài mod, nên mọi chữ được tạo trên server theo ngôn ngữ đặt ở mục `language` trong config: `vi_vn` (mặc định) hoặc `en_us`.

## Lệnh

Mọi lệnh cần quyền cấp 2 (OP). "Làng gần nhất" là làng đã ghi nhận gần bạn nhất, trong phạm vi `activeRange`.

| Lệnh | Tác dụng |
|---|---|
| `/livingvillages status` | Thông tin làng gần nhất: vị trí chuông, phong cách, số dân, giường (tổng/trống), số nhà đã xây, dự án hiện tại (mẫu nhà, tiến độ, thợ xây), thời gian chờ còn lại |
| `/livingvillages list` | Liệt kê các làng đã ghi nhận trong thế giới (chiều không gian) hiện tại |
| `/livingvillages build` | Bắt làng gần nhất xây nhà ngay, bỏ qua điều kiện giường và thời gian chờ (vẫn tính giới hạn số nhà) |
| `/livingvillages build instant` | Như trên nhưng dựng xong cả nhà ngay lập tức (hoặc hoàn thành ngay dự án đang xây). Dùng để thử |
| `/livingvillages cancel` | Hủy dự án hiện tại. Các khối đã đặt vẫn giữ nguyên |
| `/livingvillages templates [kiểu]` | Liệt kê mẫu nhà của làng gần nhất, hoặc của một kiểu: `plains`, `desert`, `savanna`, `snowy`, `taiga` |
| `/livingvillages pause` / `resume` | Tạm dừng / tiếp tục toàn bộ mod (lưu vào mục `enabled` của config) |
| `/livingvillages speed <0.1–10>` | Hệ số tốc độ xây (lưu vào config) |
| `/livingvillages reload` | Nạp lại `config/livingvillages.json` |

## Cấu hình

File `config/livingvillages.json`. Giá trị nằm ngoài khoảng cho phép sẽ dùng mặc định và ghi cảnh báo vào log. Nếu file lỗi cú pháp, mod dùng giá trị mặc định và không ghi đè file, để bạn tự sửa. Sửa xong gõ `/livingvillages reload`.

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `language` | `vi_vn` | Ngôn ngữ của mọi chữ: `vi_vn` hoặc `en_us` |
| `enabled` | `true` | Công tắc chính (`pause`/`resume`) |
| `scanIntervalTicks` | `100` | Bao lâu dò chuông quanh người chơi một lần |
| `manageIntervalTicks` | `40` | Bao lâu mỗi làng xét việc khởi công một lần |
| `scanRadius` | `64` | Bán kính dò chuông quanh mỗi người chơi |
| `villageRadius` | `48` | Bán kính quanh chuông để đếm dân và giường (tự nới rộng cho bao hết các nhà mod đã xây) |
| `villageMergeRadius` | `48` | Các chuông gần nhau hơn khoảng này được tính là cùng một làng |
| `activeRange` | `128` | Làng chỉ hoạt động khi có người chơi trong khoảng này |
| `freeBedThreshold` | `0` | Bắt đầu xây khi số giường trống ≤ giá trị này |
| `maxHousesPerVillage` | `10` | Số nhà mod xây tối đa cho mỗi làng |
| `cooldownTicks` | `24000` | Thời gian chờ sau khi xây xong một nhà (24000 = một ngày) |
| `minBuildDistance` / `maxBuildDistance` | `12` / `64` | Khoảng cách từ chuông tới nhà mới |
| `siteAttempts` | `48` | Số chỗ thử mỗi lần tìm vị trí |
| `margin` | `2` | Khoảng trống chừa quanh nhà |
| `maxHeightDifference` | `3` | Độ chênh cao tối đa của mặt đất ở chỗ xây |
| `templateYOffset` | `0` | Dịch nhà lên/xuống thêm. Độ cao sàn được tự tính, nên thường để 0 |
| `maxSiteFailures` | `5` | Sau số lần tìm chỗ thất bại liên tiếp này, làng ngừng xây cho tới khi có lệnh `/livingvillages build` |
| `blocksPerSecond` | `2.0` | Tốc độ xây cơ bản |
| `speedMultiplier` | `1.0` | Hệ số nhân tốc độ xây (lệnh `speed`) |
| `maxBlocksPerTickGlobal` | `4` | Số khối tối đa được đặt mỗi tick trên toàn server |
| `builderReach` | `8` | Thợ xây phải đứng gần khối tới mức này (theo chiều ngang) mới đặt được |
| `builderStuckTicks` | `1200` | Thời gian cho thợ xây đi tới công trường trước khi bị thay |
| `workStartTime` / `workEndTime` | `1000` / `11000` | Giờ làm việc (thời gian trong ngày, tính bằng tick) |
| `maxSkippedRatio` | `0.1` | Hủy dự án khi tỉ lệ khối bị chắn vượt mức này |
| `showBuilderHeldItem` | `true` | Thợ xây cầm khối đang đặt trên tay |
| `debugLogging` | `false` | Ghi thêm log về làng, chỗ xây và thợ xây |
| `allowTreeClearing` | `true` | Chặt cây tự nhiên trong chỗ xây |
| `maxTreeLogs` | `40` | Cây có nhiều khối gỗ hơn số này thì không bao giờ bị chặt |
| `maxTreesPerSite` | `4` | Số cây tối đa chặt cho một nhà |
| `replantSaplings` | `true` | Trồng lại một cây non cho mỗi cây đã chặt |
| `workTimeoutTicks` | `600` | Thời gian dân làng được đi tới chỗ làm việc (ví dụ trồng cây) trước khi việc được làm mà không cần họ |

## Tương thích

- **Better Village:** hỗ trợ sẵn, không cần cài đặt gì thêm. Mẫu nhà của Better Village tự được dùng. Nhà có khối nghề (lò rèn, thư viện…) bị bỏ qua dù Better Village có đặt giường trong đó.
- **Regrowth:** tường, hàng rào, đường và đuốc của Regrowth được coi như khối bình thường. Mod không chọn chỗ xây đè lên chúng, và khối Regrowth đặt vào công trường thì được bỏ qua, không bị ghi đè.
- **Guard Villagers:** lính gác không phải dân làng nên không bao giờ được chọn làm thợ xây.
- Datapack sửa bộ nhà làng cũng dùng được. Mẫu nhà rộng hơn 24×24 hoặc cao hơn 20 khối bị bỏ qua.

## Hiệu năng

- Mọi việc được chia theo chu kỳ, chỉ xử lý làng gần người chơi và không bao giờ nạp thêm chunk. Mẫu nhà được lưu tạm (cache) theo từng bộ nhà.
- Đo trên server với 3 làng xây cùng lúc: trung bình mod chỉ tốn khoảng **0,02–0,05 ms mỗi tick**.
- Thỉnh thoảng giật ngắn (vài ms, cách nhau vài giây) khi khối vừa đặt nằm trên đường đi của thợ xây. Lúc đó Minecraft tính lại đường đi, giống hệt khi người chơi đặt khối. Nhà đầu tiên sau khi bật server cũng tốn thêm chút thời gian để nạp mẫu nhà một lần.

## Giới hạn đã biết

- Làng trên đất dốc có thể không tìm được chỗ xây. Sau `maxSiteFailures` lần thất bại, làng ngừng thử cho tới khi có lệnh `/livingvillages build`.
- Chỉ hỗ trợ 5 kiểu làng vanilla.

## Gỡ mod

Nhà đã xây vẫn còn như khối bình thường. Dữ liệu duy nhất của mod là file `data/livingvillages.dat` trong thư mục của mỗi chiều không gian, có thể xóa đi. Gỡ mod không làm hỏng thế giới.
