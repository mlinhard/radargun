package org.radargun.jpa;

import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.stressors.JpaValueGenerator;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import java.util.Random;

/**
* @author Radim Vansa &lt;rvansa@redhat.com&gt;
*/
@Entity
public class BasicEntity extends JpaValueGenerator.JpaValue {

   private static Log log = LogFactory.getLog(BasicEntity.class);

   @Id
   public String id;
   @Column(length=65535)
   public String description;

   public BasicEntity() {
   }

   public BasicEntity(Object id, int size, Random random) {
      this.id = (String) id;
      description = JpaValueGenerator.getRandomString(size, random);
   }
}
