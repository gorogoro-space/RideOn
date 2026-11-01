package space.gorogoro.rideon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class RideOn extends JavaPlugin implements Listener {

    private Connection con;

    // /rideonself で、自分が他の人に乗らないようにしている人
    private final Set<UUID> selfOffPlayers = new HashSet<>();

    // config.yml の書き込み専用スレッド
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "RideOn-Save"));
    // 書き込み待ちの config.yml の内容(null なら書き込み待ちなし)
    private final AtomicReference<String> pendingConfigYaml = new AtomicReference<>();

    // ログインした人の記録(UUID → 名前と最後のログイン日時)。players.yml に保存する
    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    // 小文字にした名前 → UUID(同じ名前が複数あれば、最後にログインした人)
    private final Map<String, UUID> playersByName = new HashMap<>();
    // 書き込み待ちの players.yml の内容(null なら書き込み待ちなし)。YAML への変換は専用スレッドで行う
    private final AtomicReference<Map<UUID, PlayerRecord>> pendingPlayers = new AtomicReference<>();

    private record PlayerRecord(String name, long lastLogin) {}

    public void onEnable() {
        try{
            getServer().getPluginManager().registerEvents(this, this);
            Bukkit.getLogger().info("The Plugin Has Been Enabled!");

            // 設定ファイルが無ければ作成します
            File configFile = new File(getDataFolder(),"config.yml");
            if(!configFile.exists()){
                saveDefaultConfig();
            }

            // 自分が他の人に乗らないようにしている人を読み込む
            loadUuidSet("self-off-players", selfOffPlayers);

            // ログインした人の記録を読み込む(起動時に一度だけ)
            loadPlayers();
            // 再読み込み(PlugManX など)のときにすでにオンラインの人も記録する
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                long now = System.currentTimeMillis();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    recordPlayer(player.getUniqueId(), player.getName(), now);
                }
                requestSavePlayers();
            }

            // JDBCドライバーの指定
            Class.forName("org.sqlite.JDBC");

            // データベースに接続する なければ作成される
            con = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + File.separator +  "/rideon.db");
            con.setAutoCommit(false);      // auto commit無効

            // Statementオブジェクト作成
            Statement stmt = con.createStatement();
            stmt.setQueryTimeout(30);    // タイムアウト設定

            //テーブル作成
            stmt.executeUpdate("create table if not exists disablerideon ("
              + "id integer primary key autoincrement,"
              + "uuid string not null,"
              + "playername string not null);"
            );
            stmt.executeUpdate("create index if not exists disablerideon_idx on disablerideon (uuid);");
            stmt.executeUpdate("create table if not exists denyrideon ("
              + "id integer primary key autoincrement,"
             + "uuid string not null,"
              + "playername string not null,"
              + "owner_uuid string not null,"
              + "owner_playername string not null);"
            );
            stmt.executeUpdate("create index if not exists denyrideon_idx on denyrideon (uuid,owner_uuid);");
            con.commit();
            stmt.close();
        } catch (SQLException e) {
            Bukkit.getLogger().info(e.getMessage());
        } catch (Exception e){
            Bukkit.getLogger().info(e.getMessage());
        }
    }

    // エンティティを右クリック(左クリック)したとき
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        try {
            Player clicker = event.getPlayer();
            if (event.getRightClicked() instanceof Player) {
                // /rideonself で自分が乗らないようにしている人は乗らない
                if (selfOffPlayers.contains(clicker.getUniqueId())) {
                    return;
                }

                Player target = (Player) event.getRightClicked();

                // 無効化情報を取得
                Integer disableRideOnId = null;
                PreparedStatement prepStmt1 = con.prepareStatement("select id from disablerideon where uuid=?");
                prepStmt1.setString(1, target.getPlayer().getUniqueId().toString());
                ResultSet rs = prepStmt1.executeQuery();
                while (rs.next()) {
                  disableRideOnId = rs.getInt(1);
                }
                rs.close();
                prepStmt1.close();

                // 無効化情報を取得
                Integer denyRideOnId = null;
                PreparedStatement prepStmt2 = con.prepareStatement("select id from denyrideon where uuid=? and owner_uuid=?");
                prepStmt2.setString(1, clicker.getPlayer().getUniqueId().toString());
                prepStmt2.setString(2, target.getPlayer().getUniqueId().toString());
                ResultSet rs2 = prepStmt2.executeQuery();
                while (rs2.next()) {
                  denyRideOnId = rs2.getInt(1);
                }
                rs2.close();
                prepStmt2.close();

                // 無効中のユーザーでなければ
                if(disableRideOnId == null && denyRideOnId == null) {
                    // クリックされた人に乗車
                	List<Entity> entityList = target.getPassengers();
                	Entity lastEntity = null;
                    for (Entity e : entityList) {
                    	if(e instanceof Player && clicker.getUniqueId() != e.getUniqueId()) {
                        	lastEntity = e;
                    	}
                    }
                    if(lastEntity != null) {
                    	lastEntity.addPassenger(clicker);
                    }else {
                    	target.addPassenger(clicker);
                    }
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().info(e.getMessage());
        } catch (Exception e){
            Bukkit.getLogger().info(e.getMessage());
        }
    }

    // プレイヤーがブロックやアイテムを右クリック(左クリック)したとき
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {

    	if (!event.getAction().equals(Action.LEFT_CLICK_AIR) &&
            !event.getAction().equals(Action.LEFT_CLICK_BLOCK)
        ) {
        	// 空気を左クリックしていない且つブロックを左クリックしていない場合
        	return;
        }

        Player clicker = event.getPlayer();
        if(clicker ==null || !clicker.isSneaking()) {
        	// クリッカーが取得できない。もしくは、中腰（Shiftキーを押している）していない状態
        	return;
        }

    	List<Entity> entityList = clicker.getPassengers();
    	Player passenger = null;
        for (Entity e : entityList) {
            if (e instanceof Player) {
            	passenger = (Player)e;
            }
        }

        if(passenger != null) {
        	Vector vec = clicker.getLocation().getDirection();
        	vec = vec.normalize().multiply(1.0f);
        	passenger.leaveVehicle();
        	passenger.setVelocity(vec);
        	passenger.getWorld().playSound(passenger.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, -3.0f);
        }
    }

    // ログインしたとき、/rideondeny で探せるように UUID と今の名前を記録する
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        recordPlayer(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        requestSavePlayers();
    }

    // 乗っているプレイヤーがログアウトしたとき
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
    	Player rider = event.getPlayer();
    	Entity ridingEntity = rider.getVehicle();
    	if (ridingEntity instanceof Player) {
    		rider.eject();
    		rider.leaveVehicle();
        }
    }

    /**
     * コマンド実行時に呼び出されるメソッド
     * @see org.bukkit.plugin.java.JavaPlugin#onCommand(org.bukkit.command.CommandSender,
     *      org.bukkit.command.Command, java.lang.String, java.lang.String[])
     */
    @Override
    public boolean onCommand( CommandSender sender, Command command, String label, String[] args) {
      try{
        if((sender instanceof Player)) {
          Player sp = (Player)sender;
          if ( command.getName().equals("rideonhelp")
              || ( command.getName().equals("rideon") && args.length >= 1 && args[0].equalsIgnoreCase("help") ) ) {
            sendHelp(sp);
          }else if ( command.getName().equals("rideon") ) {
            // 実在チェック
            Integer disableRideOnId = null;
            String uuid = sp.getPlayer().getUniqueId().toString();
            String playername = sp.getPlayer().getName();
            PreparedStatement prepStmt1 = con.prepareStatement("select id from disablerideon where uuid=?");
            prepStmt1.setString(1, uuid);
            ResultSet rs = prepStmt1.executeQuery();
            while (rs.next()) {
              disableRideOnId = rs.getInt(1);
            }
            rs.close();
            prepStmt1.close();

            if(disableRideOnId == null) {
              PreparedStatement prepStmt2 = con.prepareStatement("insert into disablerideon (uuid,playername) values(?,?)");
              prepStmt2.setString(1, uuid);
              prepStmt2.setString(2, playername);
              prepStmt2.addBatch();
              prepStmt2.executeBatch();
              con.commit();
              prepStmt2.close();
              sp.sendMessage("肩車を無効にしました。");
            } else {
              PreparedStatement prepStmt3 = con.prepareStatement("delete from disablerideon where uuid = ?");
              prepStmt3.setString(1, uuid);
              prepStmt3.addBatch();
              prepStmt3.executeBatch();
              con.commit();
              prepStmt3.close();
              sp.sendMessage("肩車を有効にしました。");
            }
          }else if ( command.getName().equals("rideonself") ) {
            // 自分が他の人に乗る機能の ON / OFF を切り替える
            UUID uuid = sp.getUniqueId();
            if (selfOffPlayers.remove(uuid)) {
              sp.sendMessage("他の人に乗れるようにしました。");
            } else {
              selfOffPlayers.add(uuid);
              sp.sendMessage("他の人に乗らないようにしました。");
            }
            saveUuidSet("self-off-players", selfOffPlayers);
            requestSaveConfig();
          }else if ( command.getName().equals("rideondeny") ) {
            if(args.length != 1){
              sp.sendMessage("プレイヤー名を指定してください。");
              return true;
            }

            // ターゲットプレイヤーを取得する(ディスクを読まないよう、オンライン → players.yml → usercache の順に探す)
            String[] tp = findPlayer(args[0]);
            if(tp == null){
              sp.sendMessage("["+args[0]+"] not found.");
              sp.sendMessage("ターゲット未確認");
              return true;
            }

            // 実在チェック
            Integer denyRideOnId = null;
            String uuid = tp[0];
            String playername = tp[1];
            String ownerUuid = sp.getPlayer().getUniqueId().toString();
            String ownerPlayername = sp.getPlayer().getName();
            PreparedStatement prepStmt1 = con.prepareStatement("select id from denyrideon where uuid=? and owner_uuid=?");
            prepStmt1.setString(1, uuid);
            prepStmt1.setString(2, ownerUuid);
            ResultSet rs = prepStmt1.executeQuery();
            while (rs.next()) {
              denyRideOnId = rs.getInt(1);
            }
            rs.close();
            prepStmt1.close();

            if(denyRideOnId == null) {
              PreparedStatement prepStmt2 = con.prepareStatement("insert into denyrideon (uuid,playername,owner_uuid,owner_playername) values(?,?,?,?)");
              prepStmt2.setString(1, uuid);
              prepStmt2.setString(2, playername);
              prepStmt2.setString(3, ownerUuid);
              prepStmt2.setString(4, ownerPlayername);
              prepStmt2.addBatch();
              prepStmt2.executeBatch();
              con.commit();
              prepStmt2.close();
              sp.sendMessage(args[0] + "をブラックリストに追加しました。");
            } else {
              PreparedStatement prepStmt3 = con.prepareStatement("delete from denyrideon where uuid = ? and owner_uuid = ?");
              prepStmt3.setString(1, uuid);
              prepStmt3.setString(2, ownerUuid);
              prepStmt3.addBatch();
              prepStmt3.executeBatch();
              con.commit();
              prepStmt3.close();
              sp.sendMessage(args[0] + "をブラックリストから削除しました。");
            }
          }
        }
      } catch (SQLException e) {
        Bukkit.getLogger().info(e.getMessage());
      } catch (Exception e){
        Bukkit.getLogger().info(e.getMessage());
      }
      return true;
    }

@Override
public void onDisable(){
  try{
    // DB切断
    if (con != null) {
      con.close();
    }
  } catch (SQLException e) {
      Bukkit.getLogger().info(e.getMessage());
  } catch (Exception e){
      Bukkit.getLogger().info(e.getMessage());
  }

  // config.yml の書き込み待ちがすべて終わるまで待つ
  saveExecutor.shutdown();
  try {
    if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
      getLogger().severe("config.yml の書き込みが時間内に終わりませんでした。");
    }
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  }
  Bukkit.getLogger().info("The Plugin Has Been Disabled!");
}

  /**
   * /rideonhelp と /rideon help のヘルプを本人に表示する
   */
  private void sendHelp(Player player) {
    player.sendMessage("--- RideOn ヘルプ ---");
    player.sendMessage("プレイヤーを右クリック: その人の頭の上に乗る");
    player.sendMessage("スニークしながら左クリック: 乗っている人を投げ降ろす");
    player.sendMessage("/rideon: 他の人が自分に乗れるかを切り替える");
    player.sendMessage("/rideonself: 自分が他の人に乗るかを切り替える");
    player.sendMessage("/rideondeny <名前>: 指定した人が自分に乗れないようにする(もう一度で解除)");
    player.sendMessage("/rideonhelp: このヘルプを表示する");
  }

  /**
   * 名前からプレイヤーを探す(大文字・小文字は区別しない)。ディスクは読まない
   * オンライン → players.yml の記録 → Paper の usercache の順に探す
   * @return { UUID, 名前 }。見つからなければ null
   */
  private String[] findPlayer(String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      return new String[] { online.getUniqueId().toString(), online.getName() };
    }
    UUID uuid = playersByName.get(name.toLowerCase(Locale.ROOT));
    if (uuid != null) {
      return new String[] { uuid.toString(), players.get(uuid).name() };
    }
    OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
    if (cached != null && cached.getName() != null) {
      return new String[] { cached.getUniqueId().toString(), cached.getName() };
    }
    return null;
  }

  /**
   * ログインした人の記録をメモリへ反映する
   */
  private void recordPlayer(UUID uuid, String name, long lastLogin) {
    PlayerRecord old = players.put(uuid, new PlayerRecord(name, lastLogin));
    // 名前が変わっていれば、前の名前の索引を消す(別の人が同じ名前で上書きしていなければ)
    if (old != null) {
      playersByName.remove(old.name().toLowerCase(Locale.ROOT), uuid);
    }
    String key = name.toLowerCase(Locale.ROOT);
    UUID current = playersByName.get(key);
    if (current == null || current.equals(uuid) || players.get(current).lastLogin() <= lastLogin) {
      playersByName.put(key, uuid);
    }
  }

  /**
   * players.yml を読み込む(起動時に一度だけ)
   */
  private void loadPlayers() {
    players.clear();
    playersByName.clear();
    File file = new File(getDataFolder(), "players.yml");
    if (!file.exists()) {
      return;
    }
    ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
    if (section == null) {
      return;
    }
    for (String key : section.getKeys(false)) {
      String name = section.getString(key + ".name");
      if (name == null) {
        continue;
      }
      try {
        recordPlayer(UUID.fromString(key), name, section.getLong(key + ".last-login"));
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  /**
   * players.yml を専用スレッドで書き込む
   * メインスレッドでは記録の複製を作るだけにし、YAML への変換とファイルの書き込みは専用スレッドで行う
   * 書き込み待ちが残っている間に呼ばれた場合は、最新の内容で 1 回にまとめて書き込む
   */
  private void requestSavePlayers() {
    if (pendingPlayers.getAndSet(new HashMap<>(players)) != null) {
      return;
    }
    File dataFolder = getDataFolder();
    saveExecutor.execute(() -> {
      Map<UUID, PlayerRecord> snapshot = pendingPlayers.getAndSet(null);
      YamlConfiguration yaml = new YamlConfiguration();
      snapshot.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(e -> {
            yaml.set("players." + e.getKey() + ".name", e.getValue().name());
            yaml.set("players." + e.getKey() + ".last-login", e.getValue().lastLogin());
          });
      try {
        dataFolder.mkdirs();
        Files.writeString(new File(dataFolder, "players.yml").toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        getLogger().severe("players.yml の書き込みに失敗しました: " + e.getMessage());
      }
    });
  }

  /**
   * config.yml から UUID のリストを読み込む
   */
  private void loadUuidSet(String path, Set<UUID> set) {
    set.clear();
    for (String s : getConfig().getStringList(path)) {
      try {
        set.add(UUID.fromString(s));
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  /**
   * UUID のリストを config.yml の内容(メモリ上)へ反映する
   */
  private void saveUuidSet(String path, Set<UUID> set) {
    List<String> list = set.stream().map(UUID::toString).sorted().collect(Collectors.toList());
    getConfig().set(path, list);
  }

  /**
   * 現在の config.yml の内容を専用スレッドで書き込む
   * getConfig() へのアクセスはメインスレッドで行い、ファイルの書き込みだけを専用スレッドに任せる
   * 書き込み待ちが残っている間に呼ばれた場合は、最新の内容で 1 回にまとめて書き込む
   */
  private void requestSaveConfig() {
    String yaml = getConfig().saveToString();
    if (pendingConfigYaml.getAndSet(yaml) != null) {
      return;
    }
    File dataFolder = getDataFolder();
    saveExecutor.execute(() -> {
      String latestYaml = pendingConfigYaml.getAndSet(null);
      try {
        dataFolder.mkdirs();
        Files.writeString(new File(dataFolder, "config.yml").toPath(), latestYaml, StandardCharsets.UTF_8);
      } catch (IOException e) {
        getLogger().severe("config.yml の書き込みに失敗しました: " + e.getMessage());
      }
    });
  }

}
