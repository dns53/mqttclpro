package in.dc297.mqttclpro.activity;

import android.content.Intent;
import android.content.res.Configuration;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;

import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import in.dc297.mqttclpro.R;
import in.dc297.mqttclpro.databinding.TopicListItemBinding;
import in.dc297.mqttclpro.entity.BrokerEntity;
import in.dc297.mqttclpro.entity.Message;
import in.dc297.mqttclpro.entity.MessageEntity;
import in.dc297.mqttclpro.entity.TopicEntity;
import in.dc297.mqttclpro.mqtt.internal.MQTTClients;
import io.requery.Persistable;
import io.requery.android.QueryRecyclerAdapter;
import io.requery.query.MutableResult;
import io.requery.query.Result;
import io.requery.sql.EntityDataStore;
import io.requery.util.CloseableIterator;

public class PublishActivity extends AppCompatActivity {

    public static final String EXTRA_BROKER_ID = "EXTRA_BROKER_ID";

    private EntityDataStore<Persistable> data;
    private BrokerEntity broker;
    private ExecutorService executor;
    private TopicsListAdapter adapter;
    private long brokerId;
    private static MQTTClients mqttClients;
    private CloseableIterator<TopicEntity> topicEntityIterator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_publish);
        data = ((MQTTClientApplication)getApplication()).getData();
        brokerId = getIntent().getLongExtra(EXTRA_BROKER_ID,-1);
        if(brokerId==-1) {
            Toast.makeText(getApplicationContext(),"Unknown Error!",Toast.LENGTH_SHORT).show();
            finish();
        }
        BrokerEntity brokerEntity = data.findByKey(BrokerEntity.class,brokerId);
        broker = brokerEntity;
        setTitle(broker.getNickName() + " - published topics");
        mqttClients = MQTTClients.getInstance((MQTTClientApplication)getApplication());
        final Spinner qosSpinner = (Spinner) findViewById(R.id.qos_spinner);

        ArrayAdapter qosAdapter = ArrayAdapter.createFromResource(this, R.array.qos_array, android.R.layout.simple_spinner_item);
        qosAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        qosSpinner.setAdapter(qosAdapter);

        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        executor = Executors.newSingleThreadExecutor();
        adapter = new TopicsListAdapter();
        adapter.setExecutor(executor);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        Integer integer = data.count(TopicEntity.class).where(TopicEntity.BROKER_ID.eq(brokerId).and(TopicEntity.TYPE.eq(1))).get().value();
        if (integer == 0) {
            Toast.makeText(getApplicationContext(), "No message published on this broker yet!",Toast.LENGTH_SHORT).show();
        }
        final EditText topicEditText = (EditText) findViewById(R.id.topic_edittext);
        final EditText messageEditText = (EditText) findViewById(R.id.message_edittext);
        final Switch retainedSwitch = (Switch) findViewById(R.id.message_retained);
        Button publishButton = (Button) findViewById(R.id.publish_button);
        if (publishButton != null) {
            publishButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String topic = topicEditText.getText().toString();
                    final String message = messageEditText.getText().toString();
                    final String qos = qosSpinner.getSelectedItem().toString();
                    final boolean retained = retainedSwitch.isChecked();
                    if(topic==null || topic.equals("")){
                        Toast.makeText(getApplicationContext(),"Invalid topic value",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if(message==null){
                        Toast.makeText(getApplicationContext(),"Invalid message value",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        MqttMessage.validateQos(Integer.parseInt(qos));
                    }
                    catch(Exception e){
                        Toast.makeText(getApplicationContext(),"Invalid QOS value",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try{
                        MqttTopic.validate(topic,false);
                    }
                    catch(IllegalArgumentException ila){
                        Toast.makeText(getApplicationContext(),"Invalid topic",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    catch(IllegalStateException ise){
                        Toast.makeText(getApplicationContext(),"Invalid topic",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    topicEntityIterator = data.select(TopicEntity.class)
                            .where(TopicEntity.NAME.eq(topic)
                                    .and(TopicEntity.TYPE.eq(1)
                                            .and(TopicEntity.BROKER.eq(broker))
                                    )
                            )
                            .get()
                            .iterator();

                    MessageEntity messageEntity = new MessageEntity();
                    messageEntity.setDisplayTopic(topic);
                    messageEntity.setQOS(Integer.valueOf(qos));
                    messageEntity.setPayload(message);
                    messageEntity.setTimeStamp(new Timestamp(System.currentTimeMillis()));
                    messageEntity.setRetained(retained);
                    if(topicEntityIterator.hasNext()){
                        messageEntity.setTopic(topicEntityIterator.next());
                    }
                    else{
                        TopicEntity topicEntity = new TopicEntity();
                        topicEntity.setBroker(broker);
                        topicEntity.setQOS(0);//setting to 0 as in case of published message, qos will be set on message level
                        topicEntity.setType(1);
                        topicEntity.setName(topic);
                        messageEntity.setTopic(topicEntity);
                    }
                    messageEntity = data.insert(messageEntity);
                    if(messageEntity.getId()<=0){
                        Toast.makeText(getApplicationContext(), "Unknown error occurred!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    adapter.queryAsync();
                    mqttClients.publishMessage(broker,topic,message,Integer.parseInt(qos),retained, messageEntity.getId());
                    Log.i(PublishActivity.class.getName(),"Sending "+messageEntity.getId());
                }
            });
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem menu){
        switch(menu.getItemId()){
            case R.id.delete:
                if(adapter.toDelete!=null) {
                    data.delete(adapter.toDelete);
                }
                break;
            default:
                super.onContextItemSelected(menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
        }
        return true;
    }

    @Override
    protected void onResume() {
        adapter.queryAsync();
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        adapter.close();
        if(topicEntityIterator!=null) topicEntityIterator.close();
        super.onDestroy();
    }

    public class TopicsListAdapter extends QueryRecyclerAdapter<TopicEntity, BindingHolder<TopicListItemBinding>> implements View.OnClickListener {

        public TopicEntity toDelete;

        TopicsListAdapter(){
            super(TopicEntity.$TYPE);
        }
        @Override
        public void onClick(View v) {
            TopicListItemBinding binding = (TopicListItemBinding) v.getTag();
            if(binding!=null){
                Intent intent = new Intent(v.getContext(),MessageActivity.class);
                intent.putExtra(MessageActivity.EXTRA_TOPIC_ID,binding.getTopic().getId());
                //Toast.makeText(v.getContext(),binding.getBroker().toString(),Toast.LENGTH_SHORT).show();
                startActivity(intent);
            }
        }

        @Override
        public Result<TopicEntity> performQuery() {
            return data.select(TopicEntity.class).where(TopicEntity.BROKER_ID.eq(brokerId).and(TopicEntity.TYPE.eq(1))).get();
        }

        @Override
        public void onBindViewHolder(final TopicEntity topic, BindingHolder<TopicListItemBinding> topicListItemBindingBindingHolder, int i) {
            MutableResult<Message> messages = topic.getMessages();
            topic.setUnreadCount(0);
            topic.setLatestMessage(new MessageEntity());
            messages.each(new io.requery.util.function.Consumer<Message>() {
                @Override
                public void accept(Message message) {
                    if(topic.getLatestMessage()!=null
                            && topic.getLatestMessage().getTimeStamp()!=null
                            && topic.getLatestMessage().getTimeStamp().before(message.getTimeStamp()))
                        topic.setLatestMessage(message);
                    else if (topic.getLatestMessage()!=null && topic.getLatestMessage().getTimeStamp()==null) topic.setLatestMessage(message);
                }
            });
            topic.setCountVisibility(topic.getUnreadCount() == 0 ? View.INVISIBLE : View.VISIBLE);
            topicListItemBindingBindingHolder.binding.setTopic(topic);
        }

        @Override
        public BindingHolder<TopicListItemBinding> onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater  = LayoutInflater.from(parent.getContext());
            final TopicListItemBinding binding = TopicListItemBinding.inflate(inflater, parent, false);
            binding.getRoot().setTag(binding);
            binding.getRoot().setOnClickListener(this);
            binding.getRoot().setOnCreateContextMenuListener(new View.OnCreateContextMenuListener() {
                @Override
                public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                    MenuInflater inflater = getMenuInflater();
                    inflater.inflate(R.menu.subscribe_topic_menu, menu);
                    toDelete = (TopicEntity) binding.getTopic();
                }
            });
            TextView topicTV = (TextView) binding.getRoot().findViewById(R.id.topic_tv);
            if(getApplication().getResources().getConfiguration().orientation== Configuration.ORIENTATION_LANDSCAPE) topicTV.setMaxEms(20);
            else topicTV.setMaxEms(8);
            topicTV.setSelected(true);

            return new BindingHolder<>(binding);
        }
    }
}