package space.gorogoro.rideon;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
          
            // テーブルの実在チェック
            Boolean existsUserTable = false;
            ResultSet rs = stmt.executeQuery("select count(*) from sqlite_master where type='table' and name='disablerideon'");
            while (rs.next()) {
                if(rs.getString(1).equals("1")){
                    existsUserTable = true;
                }
            }
            rs.close();

            // テーブルが無かった場合
            if(!existsUserTable){
                //テーブル作成
                stmt.executeUpdate("create table disablerideon ("
                    + "id integer primary key autoincrement,"
                    + "uuid string not null,"
                    + "playername string not null);"
                );

                //インデックス作成
                stmt.executeUpdate("create index disablerideon on rideon (uuid);");
            }
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
                Integer rideOnId = null;
                PreparedStatement prepStmt1 = con.prepareStatement("select id from disablerideon where uuid=?");
                prepStmt1.setString(1, target.getPlayer().getUniqueId().toString());
                ResultSet rs = prepStmt1.executeQuery();
                while (rs.next()) {
                  rideOnId = rs.getInt(1);
                }
                rs.close();
                prepStmt1.close();
    
                // 無効中のユーザーでなければ
                if(rideOnId == null) {
                    // クリックされた人に乗車
                    target.addPassenger(clicker);
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
        Player target = event.getPlayer();
        if (
                (
                    event.getAction().equals(Action.LEFT_CLICK_AIR) || 
                    event.getAction().equals(Action.LEFT_CLICK_BLOCK)   // 左で空気かブロックをクリックした場合
                ) && 
                target.isSneaking() &&                                  // 中腰（Shiftキーを押している）状態
                target.getPassengers().get(0) != null &&                // クリックした人に乗車している
                target.getPassengers().get(0) instanceof Player
        ) {
            Player passenger = (Player)target.getPassengers().get(0);
            Vector vec = target.getLocation().getDirection();
            vec = vec.normalize().multiply(1.0f);
            passenger.leaveVehicle();
            passenger.setVelocity(vec);
            passenger.getWorld().playSound(passenger.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, -3.0f);
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
              Integer rideOnId = null;
              String uuid = sp.getPlayer().getUniqueId().toString();
              String playername = sp.getPlayer().getName();
              PreparedStatement prepStmt1 = con.prepareStatement("select id from disablerideon where uuid=?");
              prepStmt1.setString(1, uuid);
              ResultSet rs = prepStmt1.executeQuery();
              while (rs.next()) {
                rideOnId = rs.getInt(1);
              }
              rs.close();
              prepStmt1.close();
              
              if(rideOnId == null) {
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
