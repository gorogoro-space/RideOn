package space.gorogoro.rideon;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class RideOn extends JavaPlugin implements Listener {

    private Connection con;

    public void onEnable() {
        try{
            getServer().getPluginManager().registerEvents(this, this);
            Bukkit.getLogger().info("The Plugin Has Been Enabled!");

            // 設定ファイルが無ければ作成します
            File configFile = new File(this.getDataFolder() + "/config.yml");
            if(!configFile.exists()){
                saveDefaultConfig();
            }

            // JDBCドライバーの指定
            Class.forName("org.sqlite.JDBC");

            // データベースに接続する なければ作成される
            con = DriverManager.getConnection("jdbc:sqlite:" + this.getDataFolder() + "/rideon.db");
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
          if ( command.getName().equals("rideon") ) {
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
          }else if ( command.getName().equals("rideondeny") ) {
            if(args.length != 1){
              sp.sendMessage("プレイヤー名を指定してください。");
              return true;
            }

            // ターゲットプレイヤーを取得する
            OfflinePlayer tp = null;
            for ( OfflinePlayer player : Bukkit.getOfflinePlayers() ) {
              if ( player.getName().equals(args[0]) ) {
                tp = player;
                break;
              }
            }
            if(tp == null){
              sp.sendMessage("["+args[0]+"] not found.");
              sp.sendMessage("ターゲット未確認");
              return true;
            }

            // 実在チェック
            Integer denyRideOnId = null;
            String uuid = tp.getPlayer().getUniqueId().toString();
            String playername = tp.getPlayer().getName();
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
  Bukkit.getLogger().info("The Plugin Has Been Disabled!");
}

}
