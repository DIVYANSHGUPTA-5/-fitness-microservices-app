import React, { useState, useEffect } from 'react'
import { Grid, Card, CardContent, Typography } from "@mui/material";
import { getActivites } from '../services/api';
import { useNavigate } from 'react-router';

const ActivityList = () => {

  const [activities , setActivities] = useState([]);
  const navigate = useNavigate();

  const fetchActivities = async () => {
    try {
      const response = await getActivites();
      setActivities(response.data);
    } catch (error) {
      console.error(error);
    }
  };

  useEffect(() => { 
    fetchActivities();
  }, []);

  return (
    <Grid container spacing={2}>
      {activities.map((activity) => (
        <Grid key={activity.id} item xs={12} sm={6} md={4}>
          <Card
            sx={{cursor: 'pointer'}}
            onClick={() => navigate(`/activities/${activity.id}`)}
          >
            <CardContent>
              <Typography variant='h6'>{activity.type}</Typography>
              <Typography>Duration: {activity.duration}</Typography>
              <Typography>Calories: {activity.caloriesBurned}</Typography>
            </CardContent>
          </Card>
        </Grid>
      ))}
    </Grid>
  )
}

export default ActivityList