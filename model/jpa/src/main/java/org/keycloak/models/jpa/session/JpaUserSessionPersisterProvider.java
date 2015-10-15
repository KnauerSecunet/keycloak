package org.keycloak.models.jpa.session;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.session.PersistentClientSessionAdapter;
import org.keycloak.models.session.PersistentClientSessionModel;
import org.keycloak.models.session.PersistentUserSessionAdapter;
import org.keycloak.models.session.PersistentUserSessionModel;
import org.keycloak.models.session.UserSessionPersisterProvider;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class JpaUserSessionPersisterProvider implements UserSessionPersisterProvider {

    private final KeycloakSession session;
    private final EntityManager em;

    public JpaUserSessionPersisterProvider(KeycloakSession session, EntityManager em) {
        this.session = session;
        this.em = em;
    }

    @Override
    public void createUserSession(UserSessionModel userSession, boolean offline) {
        PersistentUserSessionAdapter adapter = new PersistentUserSessionAdapter(userSession);
        PersistentUserSessionModel model = adapter.getUpdatedModel();

        PersistentUserSessionEntity entity = new PersistentUserSessionEntity();
        entity.setUserSessionId(model.getUserSessionId());
        entity.setRealmId(adapter.getRealm().getId());
        entity.setUserId(adapter.getUser().getId());
        entity.setOffline(offline);
        entity.setLastSessionRefresh(model.getLastSessionRefresh());
        entity.setData(model.getData());
        em.persist(entity);
        em.flush();
    }

    @Override
    public void createClientSession(ClientSessionModel clientSession, boolean offline) {
        PersistentClientSessionAdapter adapter = new PersistentClientSessionAdapter(clientSession);
        PersistentClientSessionModel model = adapter.getUpdatedModel();

        PersistentClientSessionEntity entity = new PersistentClientSessionEntity();
        entity.setClientSessionId(clientSession.getId());
        entity.setClientId(clientSession.getClient().getId());
        entity.setTimestamp(clientSession.getTimestamp());
        entity.setOffline(offline);
        entity.setUserSessionId(clientSession.getUserSession().getId());
        entity.setData(model.getData());
        em.persist(entity);
        em.flush();
    }

    @Override
    public void updateUserSession(UserSessionModel userSession, boolean offline) {
        PersistentUserSessionAdapter adapter;
        if (userSession instanceof PersistentUserSessionAdapter) {
            adapter = (PersistentUserSessionAdapter) userSession;
        } else {
            adapter = new PersistentUserSessionAdapter(userSession);
        }

        PersistentUserSessionModel model = adapter.getUpdatedModel();

        PersistentUserSessionEntity entity = em.find(PersistentUserSessionEntity.class, new PersistentUserSessionEntity.Key(userSession.getId(), offline));
        if (entity == null) {
            throw new ModelException("UserSession with ID " + userSession.getId() + ", offline: " + offline + " not found");
        }
        entity.setLastSessionRefresh(model.getLastSessionRefresh());
        entity.setData(model.getData());
    }

    @Override
    public void removeUserSession(String userSessionId, boolean offline) {
        em.createNamedQuery("deleteClientSessionsByUserSession")
                .setParameter("userSessionId", userSessionId)
                .setParameter("offline", offline)
                .executeUpdate();

        PersistentUserSessionEntity sessionEntity = em.find(PersistentUserSessionEntity.class, new PersistentUserSessionEntity.Key(userSessionId, offline));
        if (sessionEntity != null) {
            em.remove(sessionEntity);
            em.flush();
        }
    }

    @Override
    public void removeClientSession(String clientSessionId, boolean offline) {
        PersistentClientSessionEntity sessionEntity = em.find(PersistentClientSessionEntity.class, new PersistentClientSessionEntity.Key(clientSessionId, offline));
        if (sessionEntity != null) {
            em.remove(sessionEntity);

            // Remove userSession if it was last clientSession
            List<PersistentClientSessionEntity> clientSessions = getClientSessionsByUserSession(sessionEntity.getUserSessionId(), offline);
            if (clientSessions.size() == 0) {
                PersistentUserSessionEntity userSessionEntity = em.find(PersistentUserSessionEntity.class, new PersistentUserSessionEntity.Key(sessionEntity.getUserSessionId(), offline));
                if (userSessionEntity != null) {
                    em.remove(userSessionEntity);
                }
            }

            em.flush();
        }
    }

    private List<PersistentClientSessionEntity> getClientSessionsByUserSession(String userSessionId, boolean offline) {
        TypedQuery<PersistentClientSessionEntity> query = em.createNamedQuery("findClientSessionsByUserSession", PersistentClientSessionEntity.class);
        query.setParameter("userSessionId", userSessionId);
        query.setParameter("offline", offline);
        return query.getResultList();
    }



    @Override
    public void onRealmRemoved(RealmModel realm) {
        int num = em.createNamedQuery("deleteClientSessionsByRealm").setParameter("realmId", realm.getId()).executeUpdate();
        num = em.createNamedQuery("deleteUserSessionsByRealm").setParameter("realmId", realm.getId()).executeUpdate();
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        int num = em.createNamedQuery("deleteClientSessionsByClient").setParameter("clientId", client.getId()).executeUpdate();
        num = em.createNamedQuery("deleteDetachedUserSessions").executeUpdate();
    }

    @Override
    public void onUserRemoved(RealmModel realm, UserModel user) {
        int num = em.createNamedQuery("deleteClientSessionsByUser").setParameter("userId", user.getId()).executeUpdate();
        num = em.createNamedQuery("deleteUserSessionsByUser").setParameter("userId", user.getId()).executeUpdate();
    }

    @Override
    public void clearDetachedUserSessions() {
        int num = em.createNamedQuery("deleteDetachedClientSessions").executeUpdate();
        num = em.createNamedQuery("deleteDetachedUserSessions").executeUpdate();
    }

    @Override
    public void updateAllTimestamps(int time) {
        int num = em.createNamedQuery("updateClientSessionsTimestamps").setParameter("timestamp", time).executeUpdate();
        num = em.createNamedQuery("updateUserSessionsTimestamps").setParameter("lastSessionRefresh", time).executeUpdate();
    }

    @Override
    public List<UserSessionModel> loadUserSessions(int firstResult, int maxResults, boolean offline) {
        TypedQuery<PersistentUserSessionEntity> query = em.createNamedQuery("findUserSessions", PersistentUserSessionEntity.class);
        query.setParameter("offline", offline);

        if (firstResult != -1) {
            query.setFirstResult(firstResult);
        }
        if (maxResults != -1) {
            query.setMaxResults(maxResults);
        }

        List<PersistentUserSessionEntity> results = query.getResultList();
        List<UserSessionModel> result = new ArrayList<>();
        List<String> userSessionIds = new ArrayList<>();
        for (PersistentUserSessionEntity entity : results) {
            result.add(toAdapter(entity));
            userSessionIds.add(entity.getUserSessionId());
        }

        TypedQuery<PersistentClientSessionEntity> query2 = em.createNamedQuery("findClientSessionsByUserSessions", PersistentClientSessionEntity.class);
        query2.setParameter("userSessionIds", userSessionIds);
        query2.setParameter("offline", offline);
        List<PersistentClientSessionEntity> clientSessions = query2.getResultList();

        // Assume both userSessions and clientSessions ordered by userSessionId
        int j=0;
        for (UserSessionModel ss : result) {
            PersistentUserSessionAdapter userSession = (PersistentUserSessionAdapter) ss;
            List<ClientSessionModel> currentClientSessions = userSession.getClientSessions(); // This is empty now and we want to fill it

            boolean next = true;
            while (next && j<clientSessions.size()) {
                PersistentClientSessionEntity clientSession = clientSessions.get(j);
                if (clientSession.getUserSessionId().equals(userSession.getId())) {
                    PersistentClientSessionAdapter clientSessAdapter = toAdapter(userSession.getRealm(), userSession, clientSession);
                    currentClientSessions.add(clientSessAdapter);
                    j++;
                } else {
                    next = false;
                }
            }
        }



        return result;
    }

    private PersistentUserSessionAdapter toAdapter(PersistentUserSessionEntity entity) {
        RealmModel realm = session.realms().getRealm(entity.getRealmId());
        UserModel user = session.users().getUserById(entity.getUserId(), realm);

        PersistentUserSessionModel model = new PersistentUserSessionModel();
        model.setUserSessionId(entity.getUserSessionId());
        model.setLastSessionRefresh(entity.getLastSessionRefresh());
        model.setData(entity.getData());

        List<ClientSessionModel> clientSessions = new LinkedList<>();
        return new PersistentUserSessionAdapter(model, realm, user, clientSessions);
    }

    private PersistentClientSessionAdapter toAdapter(RealmModel realm, PersistentUserSessionAdapter userSession, PersistentClientSessionEntity entity) {
        ClientModel client = realm.getClientById(entity.getClientId());

        PersistentClientSessionModel model = new PersistentClientSessionModel();
        model.setClientSessionId(entity.getClientSessionId());
        model.setClientId(entity.getClientId());
        model.setUserSessionId(userSession.getId());
        model.setUserId(userSession.getUser().getId());
        model.setTimestamp(entity.getTimestamp());
        model.setData(entity.getData());
        return new PersistentClientSessionAdapter(model, realm, client, userSession);
    }

    @Override
    public int getUserSessionsCount(boolean offline) {
        Query query = em.createNamedQuery("findUserSessionsCount");
        query.setParameter("offline", offline);
        Number n = (Number) query.getSingleResult();
        return n.intValue();
    }

    @Override
    public void close() {

    }
}
