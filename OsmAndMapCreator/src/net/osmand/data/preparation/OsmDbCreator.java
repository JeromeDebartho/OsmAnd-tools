package net.osmand.data.preparation;

import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import net.osmand.data.City.CityType;
import net.osmand.osm.edit.Entity;
import net.osmand.osm.edit.Entity.EntityId;
import net.osmand.osm.edit.Entity.EntityType;
import net.osmand.osm.edit.Node;
import net.osmand.osm.edit.OSMSettings.OSMTagKey;
import net.osmand.osm.edit.Relation;
import net.osmand.osm.edit.Way;
import net.osmand.osm.io.IOsmStorageFilter;
import net.osmand.osm.io.OsmBaseStorage;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class OsmDbCreator implements IOsmStorageFilter {

	
	private static final Log log = LogFactory.getLog(OsmDbCreator.class);
	public static final int SHIFT_ID = 6;
	public static final int BATCH_SIZE_OSM = 100000;

	// do not store these tags in the database, just ignore them
	final String[] tagsToIgnore= {"created_by","source","converted_by"};
	
	DBDialect dialect;
	int currentCountNode = 0;
	private PreparedStatement prepNode;
	int allNodes = 0;

	int currentRelationsCount = 0;
	private PreparedStatement prepRelations;
	int allRelations = 0;

	int currentWaysCount = 0;
	private PreparedStatement prepWays;
	int allWays = 0;
	
	private PreparedStatement delNode;
	private PreparedStatement delRelations;
	private PreparedStatement delWays;
	private TLongHashSet nodeIds;
	private TLongHashSet wayIds;
	private TLongHashSet relationIds;

	private Connection dbConn;

	private final int shiftId;
	private final int additionId;
	private boolean ovewriteIds;

	private static boolean VALIDATE_DUPLICATES = false;
	private boolean backwardComptibleIds;
	private TLongObjectHashMap<Long> generatedIds = new TLongObjectHashMap<Long>();
	private TLongObjectHashMap<Long> hashes = new TLongObjectHashMap<Long>();
	private TLongSet idSet = new TLongHashSet();
	


	public OsmDbCreator(int additionId, int shiftId, boolean ovewriteIds) {
		this.additionId = additionId;
		this.shiftId = shiftId;
		this.ovewriteIds = ovewriteIds;
	}
	
	public OsmDbCreator() {
		this(0, 0, false);
	}
	
	
	private long convertId(Entity e) {
		long id = e.getId();
		if (backwardComptibleIds) {
			return id;
		}
		if (!ovewriteIds && shiftId > 0) {
			return (id << shiftId) + additionId;
		}
		int ord = EntityType.valueOf(e).ordinal();
		if (e instanceof Node) {
			int hash = getNodeHash(e);
			return getConvertId(id, ord, hash);
		} else if (e instanceof Way) {
			TLongArrayList lids = ((Way) e).getNodeIds();
			long hash = 0;
			for (int i = 0; i < lids.size(); i++) {
				Long ld = getGeneratedId(lids.get(i), 0);
				Long hd = getHash(lids.get(i), 0);
				if(hd != null) {
					hash += hd;
				}
				if (ld != null) {
					lids.set(i, ld);
				}
			}
			return getConvertId(id, ord, hash);
		} else {
			Relation r = (Relation) e;
			Map<EntityId, EntityId> p = new HashMap<Entity.EntityId, Entity.EntityId>();
			for (EntityId i : r.getMemberIds()) {
				if (i.getType() != EntityType.RELATION) {
					Long ll = getGeneratedId(i.getId().longValue(), i.getType().ordinal());
					if (ll != null) {
						p.put(i, new EntityId(i.getType(), ll));
					}
				}
			}
			Iterator<Entry<EntityId, EntityId>> it = p.entrySet().iterator();
			while (it.hasNext()) {
				Entry<EntityId, EntityId> es = it.next();
				String role = r.getModifiableMembersMap().remove(es.getKey());
				r.getModifiableMembersMap().put(es.getValue(), role);
			}
			return id;
		}
	}

	private int getNodeHash(Entity e) {
		int y = MapUtils.get31TileNumberY(((Node) e).getLatitude());
		int x = MapUtils.get31TileNumberX(((Node) e).getLongitude());
		int hash = (x + y) >> 10;
		return hash;
	}

	private Long getHash(long l, int ord) {
		if(l < 0) {
			long lid = (l << shiftId) + additionId;
			long fid = (lid << 2) + ord;
			return hashes.get(fid);
		}
		return hashes.get((l << 2) + ord);
	}

	private Long getGeneratedId(long l, int ord) {
		if(l < 0) {
			long lid = (l << shiftId) + additionId;
			long fid = (lid << 2) + ord;
			return generatedIds.get(fid);
		}
		return generatedIds.get((l << 2) + ord);
	}

	private long getConvertId(long id, int ord, long hash) {
		if(id < 0) {
			long lid = (id << shiftId) + additionId;
			long fid = (lid << 2) + ord;
			generatedIds.put(fid, lid);
			hashes.put(fid, hash);
			return lid;
		}
		int l = (int) (hash & ((1 << (SHIFT_ID - 1)) - 1));
		long cid = (id << SHIFT_ID) + (ord % 2) + l << 1;
		long fid = (id << 2) + ord;
		generatedIds.put(fid, cid);
		hashes.put(fid, hash);
		return cid;
	}
	

	public void initDatabase(DBDialect dialect, Object databaseConn, boolean create) throws SQLException {

		this.dialect = dialect;
		this.dbConn = (Connection) databaseConn;
		// prepare tables
		Statement stat = dbConn.createStatement();
		if (create) {
			dialect.deleteTableIfExists("node", stat);
			stat.executeUpdate("create table node (id bigint primary key, latitude double, longitude double, tags blob)"); //$NON-NLS-1$
			stat.executeUpdate("create index IdIndex ON node (id)"); //$NON-NLS-1$
			dialect.deleteTableIfExists("ways", stat);
			stat.executeUpdate("create table ways (id bigint, node bigint, ord smallint, tags blob, boundary smallint, primary key (id, ord))"); //$NON-NLS-1$
			stat.executeUpdate("create index IdWIndex ON ways (id)"); //$NON-NLS-1$
			dialect.deleteTableIfExists("relations", stat);
			stat.executeUpdate("create table relations (id bigint, member bigint, type smallint, role varchar(1024), ord smallint, tags blob, del int, primary key (id, ord, del))"); //$NON-NLS-1$
			stat.executeUpdate("create index IdRIndex ON relations (id)"); //$NON-NLS-1$
			stat.close();
		}
		prepNode = dbConn.prepareStatement("insert into node values (?, ?, ?, ?)"); //$NON-NLS-1$
		prepWays = dbConn.prepareStatement("insert into ways values (?, ?, ?, ?, ?)"); //$NON-NLS-1$
		prepRelations = dbConn.prepareStatement("insert into relations values (?, ?, ?, ?, ?, ?, ?)"); //$NON-NLS-1$
		dbConn.setAutoCommit(false);
	}

	public void finishLoading() throws SQLException {
		if (currentCountNode > 0) {
			prepNode.executeBatch();
		}
		prepNode.close();
		if (currentWaysCount > 0) {
			prepWays.executeBatch();
		}
		prepWays.close();
		if (currentRelationsCount > 0) {
			prepRelations.executeBatch();
		}
		prepRelations.close();
		if (delNode != null) {
			delNode.close();
		}
		if (delWays != null) {
			delWays.close();
		}
		if (delRelations != null) {
			delRelations.close();
		}
	}
	
	
	
	private void checkEntityExists(Entity e, long id, boolean delete) throws SQLException {
		if (nodeIds == null) {
			nodeIds = new TLongHashSet();
			wayIds = new TLongHashSet();
			relationIds = new TLongHashSet();
			delNode = dbConn.prepareStatement("delete from node where id = ?"); //$NON-NLS-1$
			delWays = dbConn.prepareStatement("delete from ways where id = ?"); //$NON-NLS-1$
			delRelations = dbConn.prepareStatement("delete from relations where id = ? and del = ?"); //$NON-NLS-1$
		}
		if (e instanceof Node) {
			nodeIds.add(id);
		} else if (e instanceof Way) {
			wayIds.add(id);
		} else if (e instanceof Relation) {
			relationIds.add(id);
		}
		prepNode.executeBatch();
		prepWays.executeBatch();
		prepRelations.executeBatch();
		if (e instanceof Node) {
			delNode.setLong(1, id);
			delNode.execute();
		} else if (e instanceof Way) {
			delWays.setLong(1, id);
			delWays.execute();
		} else if (e instanceof Relation) {
			delRelations.setLong(1, id);
			delRelations.setLong(2, delete ? 1 : 0);
			delRelations.execute();
		}
	}

	@Override
	public boolean acceptEntityToLoad(OsmBaseStorage storage, EntityId entityId, Entity e) {
		// put all nodes into temporary db to get only required nodes after loading all data
		if(VALIDATE_DUPLICATES) {
			long l = (e.getId() << 2) + entityId.getType().ordinal();
			if(!idSet.add(l)) {
				throw new IllegalStateException("Duplicate id '" + e.getId() +"' " + entityId.getType());
			}
		}
		boolean osmChange = storage.isOsmChange();
		try {
			e.removeTags(tagsToIgnore);
			ByteArrayOutputStream tags = new ByteArrayOutputStream();
			try {
				for (Entry<String, String> i : e.getTags().entrySet()) {
					// UTF-8 default
					tags.write(i.getKey().getBytes("UTF-8"));
					tags.write(0);
					tags.write(i.getValue().getBytes("UTF-8"));
					tags.write(0);
				}
			} catch (IOException es) {
				throw new RuntimeException(es);
			}
			long id = convertId(e);
			boolean delete = "delete".equals(e.getTag("osmand_change"));
			if (osmChange || ovewriteIds || e instanceof Relation) {
				checkEntityExists(e, id, delete);
			}
			if (e instanceof Node) {
				currentCountNode++;
				if (!e.getTags().isEmpty()) {
					allNodes++;
				}
				prepNode.setLong(1, id);
				prepNode.setDouble(2, ((Node) e).getLatitude());
				prepNode.setDouble(3, ((Node) e).getLongitude());
				prepNode.setBytes(4, tags.toByteArray());
				prepNode.addBatch();
				if (currentCountNode >= BATCH_SIZE_OSM) {
					prepNode.executeBatch();
					dbConn.commit(); // clear memory
					currentCountNode = 0;
				}
			} else if (e instanceof Way) {
				allWays++;
				int ord = 0;
				TLongArrayList nodeIds = ((Way) e).getNodeIds();
				boolean city = CityType.valueFromString(((Way) e).getTag(OSMTagKey.PLACE)) != null;
				int boundary = ((Way) e).getTag(OSMTagKey.BOUNDARY) != null || city ? 1 : 0;
				for (int j = 0; j < nodeIds.size(); j++) {
					currentWaysCount++;
					if (ord == 0) {
						prepWays.setBytes(4, tags.toByteArray());
					}
					prepWays.setLong(1, id);
					prepWays.setLong(2, nodeIds.get(j));
					prepWays.setLong(3, ord++);
					prepWays.setInt(5, boundary);
					prepWays.addBatch();
				}
				if (currentWaysCount >= BATCH_SIZE_OSM) {
					prepWays.executeBatch();
					dbConn.commit(); // clear memory
					currentWaysCount = 0;
				}
			} else if (!osmChange) {
				// osm change can't handle relations properly
				allRelations++;
				short ord = 0;
				for (Entry<EntityId, String> i : ((Relation) e).getMembersMap().entrySet()) {
					currentRelationsCount++;
					if (ord == 0) {
						prepRelations.setBytes(6, tags.toByteArray());
					}
					prepRelations.setLong(1, id);
					prepRelations.setLong(2, i.getKey().getId());
					prepRelations.setLong(3, i.getKey().getType().ordinal());
					prepRelations.setString(4, i.getValue());
					prepRelations.setLong(5, ord++);
					prepRelations.setInt(7, delete ? 1 : 0);
					prepRelations.addBatch();
				}
				if (currentRelationsCount >= BATCH_SIZE_OSM) {
					prepRelations.executeBatch();
					dbConn.commit(); // clear memory
					currentRelationsCount = 0;
				}
			}

		} catch (SQLException ex) {
			log.error("Could not save in db (entity " + entityId + ") ", ex); //$NON-NLS-1$
		}
		// do not add to storage
		return false;
	}



	public int getAllNodes() {
		return allNodes;
	}

	public int getAllRelations() {
		return allRelations;
	}

	public int getAllWays() {
		return allWays;
	}

	public void setBackwardCompatibleIds(boolean backwardComptibleIds) {
		this.backwardComptibleIds = backwardComptibleIds;
		
	}
	

}
